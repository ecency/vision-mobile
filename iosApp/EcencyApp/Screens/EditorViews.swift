import EcencyCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

// The RN editor (screens/editor) and drafts screen over the shared EditorModel, DraftsModel, UploadsModel,
// SnippetsModel and CommunityPickerModel. The text itself is edited in a UITextView so the selection is known
// in UTF-16 units, the unit the shared format functions (and RN) count in.

/// The body field. Typing lives in the text view; when the model rewrites the text (a toolbar action, an upload
/// placeholder), `revision` goes up and the view takes the model's text and selection over.
///
/// The view sits in the one scroll view that also holds the community row, title and tags, so those scroll away with
/// the body and the body gets the room while the keyboard is up (tester: "Window for editing drafts much too small").
/// RN's markdownEditorView keeps title and tags fixed above a body input that scrolls on its own.
/// - Up to `growsUpTo` UTF-16 units the view does not scroll itself: it grows with its text (at least `minHeight`,
///   the visible height of the scroll view) and the outer scroll view follows the caret.
/// - Above it the view is `minHeight` tall and scrolls itself (it follows its own caret), because a text view that
///   does not scroll lays out all of its text on every keystroke: measured on the simulator, about 1.2 ms per 1,000
///   units, so 10,000 units (about 1,500 words) take some 12 ms a key, inside a 60 Hz frame, and 345,000 units (a
///   50,000 word draft) about 400 ms; a scrolling one lays out what is visible (12 ms a key at 50,000 words). There a
///   drag on the text that reaches its top or bottom goes on in the outer scroll view (`chain`), so the title stays
///   reachable. It turns back into a growing view below `scrollsDownTo`, so it does not flip at every key.
struct BodyTextView: UIViewRepresentable {
    let text: String
    let selection: CoreTextSelection
    let revision: Int32
    let editable: Bool
    let placeholder: String
    var minHeight: CGFloat = 0
    /// Goes up when the body comes back (the preview closed): the caret is scrolled into view again.
    var revealToken: Int = 0
    let onChange: (String, NSRange) -> Void

    static let growsUpTo = 10_000
    static let scrollsDownTo = 8_000

    /// Whether a body of [length] UTF-16 units scrolls itself, given whether it [scrolls] now.
    static func scrollsItself(length: Int, scrolls: Bool) -> Bool {
        scrolls ? length > scrollsDownTo : length > growsUpTo
    }

    /// The hardware keyboard's bar and a hidden keyboard leave less than this over the window; a keyboard takes more.
    static let keyboardMinimum: CGFloat = 150

    /// How much of [window] (window coordinates) the keyboard at [frame] (window coordinates) covers.
    static func keyboardOverlap(_ frame: CGRect, in window: CGRect) -> CGFloat {
        let covered = window.intersection(frame)
        return covered.isNull ? 0 : covered.height
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator
        view.isScrollEnabled = Self.scrollsItself(length: (text as NSString).length, scrolls: false)
        context.coordinator.view = view
        context.coordinator.shownText = text
        context.coordinator.revealToken = revealToken
        view.font = UIFont.preferredFont(forTextStyle: .body)
        view.adjustsFontForContentSizeCategory = true
        view.backgroundColor = .clear
        view.textContainerInset = UIEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)
        view.accessibilityIdentifier = "editor-body"
        view.accessibilityLabel = placeholder
        view.text = text
        view.selectedRange = range(in: text)
        context.coordinator.revision = revision
        // back from the preview: a new view, the caret is shown again once it is laid out
        if revealToken > 0 { context.coordinator.reveal(bodyToTop: false, needsFocus: false) }
        return view
    }

    private func range(in text: String) -> NSRange {
        let length = (text as NSString).length
        let start = min(max(Int(selection.start), 0), length)
        let end = min(max(Int(selection.end), start), length)
        return NSRange(location: start, length: end - start)
    }

    func updateUIView(_ view: UITextView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        view.isEditable = editable
        if coordinator.revision != revision {
            coordinator.revision = revision
            coordinator.applying = true
            if view.text != text { view.text = text }
            view.selectedRange = range(in: text)
            coordinator.applying = false
            coordinator.textChanged()
        }
        coordinator.updateMode()
        coordinator.widthChanged()
        if coordinator.revealToken != revealToken {
            coordinator.revealToken = revealToken
            coordinator.reveal(bodyToTop: false, needsFocus: false)
        }
        // the new height is laid out after this update: follow the caret once it is (once a keystroke: the text
        // view's own change only marks the text as changed)
        if coordinator.shownText != text {
            coordinator.shownText = text
            coordinator.reveal(bodyToTop: false)
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite, width > 0 else { return nil }
        if uiView.isScrollEnabled { return CGSize(width: width, height: minHeight) }
        return CGSize(width: width, height: max(context.coordinator.fittedHeight(uiView, width: width), minHeight))
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: BodyTextView
        var revision: Int32 = -1
        var revealToken = 0
        var applying = false
        var shownText = ""
        weak var view: UITextView?
        /// The text's version, for the fitted height: SwiftUI asks for the size several times a layout.
        private var textVersion = 0
        private var fit: (width: CGFloat, version: Int, size: UIContentSizeCategory, height: CGFloat)?
        /// What the keyboard covers of the window, from its own frame notifications (0 for the hardware keyboard's bar).
        private(set) var keyboardUp = false
        private var observers: [NSObjectProtocol] = []
        /// The header is moved away once a focus: room or footer changes never move it again for someone who
        /// scrolled back up to the title. `movedTo` is where that left the scroll view.
        private var movedUp = false
        private var movedTo: CGFloat?
        private var width: CGFloat = 0
        private var pending = false
        private var pendingToTop = false
        private var pendingNeedsFocus = true
        private var chaining = false
        private var lastInnerY: CGFloat?

        init(_ parent: BodyTextView) {
            self.parent = parent
            super.init()
            let center = NotificationCenter.default
            observers.append(center.addObserver(forName: UIResponder.keyboardWillChangeFrameNotification, object: nil, queue: .main) { [weak self] note in
                self?.keyboardFrame(note)
            })
            observers.append(center.addObserver(forName: UIResponder.keyboardWillHideNotification, object: nil, queue: .main) { [weak self] _ in
                self?.keyboardUp = false
            })
        }

        deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }

        private func keyboardFrame(_ note: Notification) {
            guard let view, let window = view.window, let end = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            // the frame is in the screen's coordinates
            let space = (note.object as? UIScreen)?.coordinateSpace ?? window.screen.coordinateSpace
            let overlap = BodyTextView.keyboardOverlap(window.convert(end, from: space), in: window.bounds)
            let up = overlap > BodyTextView.keyboardMinimum
            let cameUp = up && !keyboardUp
            keyboardUp = up
            // the keyboard came up over the focused body: the body moves to the top once the editor took its room
            if cameUp { reveal(bodyToTop: true) }
        }

        func textChanged() {
            textVersion += 1
        }

        func fittedHeight(_ view: UITextView, width: CGFloat) -> CGFloat {
            let size = view.traitCollection.preferredContentSizeCategory
            if let fit, fit.width == width, fit.version == textVersion, fit.size == size { return fit.height }
            let height = ceil(view.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height)
            fit = (width, textVersion, size, height)
            return height
        }

        /// Growing or scrolling by the text's length (see BodyTextView).
        func updateMode() {
            guard let view else { return }
            let scrolls = BodyTextView.scrollsItself(length: (view.text as NSString).length, scrolls: view.isScrollEnabled)
            guard scrolls != view.isScrollEnabled else { return }
            view.isScrollEnabled = scrolls
            fit = nil
            view.invalidateIntrinsicContentSize()
            if scrolls { DispatchQueue.main.async { view.scrollRangeToVisible(view.selectedRange) } }
            reveal(bodyToTop: scrolls)
        }

        /// Another width (rotation, a fold, a pane) with the keyboard up: a body that was moved to the top and left
        /// there goes to the top again after the text reflowed; otherwise only the caret is kept in view.
        func widthChanged() {
            guard let view, view.bounds.width > 0, view.bounds.width != width else { return }
            let first = width == 0
            width = view.bounds.width
            guard !first, view.isFirstResponder, keyboardUp else { return }
            let scroll = Self.scrollView(around: view)
            var leftThere = false
            if let movedTo, let scroll { leftThere = abs(scroll.contentOffset.y - movedTo) < 2 }
            if leftThere { movedUp = false }
            reveal(bodyToTop: leftThere)
        }

        func textViewDidBeginEditing(_ view: UITextView) {
            movedUp = false
            movedTo = nil
            // the keyboard may already be up (focus came from the title or tags): then it sends no new frame
            reveal(bodyToTop: keyboardUp)
        }

        func textViewDidEndEditing(_ view: UITextView) {
            movedUp = false
            movedTo = nil
        }

        func textViewDidChange(_ view: UITextView) {
            guard !applying else { return }
            textChanged()
            parent.onChange(view.text ?? "", view.selectedRange)
        }

        func textViewDidChangeSelection(_ view: UITextView) {
            guard !applying else { return }
            parent.onChange(view.text ?? "", view.selectedRange)
        }

        /// A long body scrolls itself: a drag that reaches its top or bottom goes on in the editor's scroll view.
        func scrollViewDidScroll(_ inner: UIScrollView) {
            guard !chaining, inner.isScrollEnabled, inner.isTracking || inner.isDecelerating, let outer = Self.scrollView(around: inner) else {
                lastInnerY = inner.contentOffset.y
                return
            }
            chaining = true
            Self.chain(inner: inner, outer: outer, from: lastInnerY ?? inner.contentOffset.y)
            chaining = false
            lastInnerY = inner.contentOffset.y
        }

        /// Moves [outer] instead of [inner]: scrolling down, the header goes away first; pulled past the text's top,
        /// the header comes back. [from] is where [inner] was before this move.
        static func chain(inner: UIScrollView, outer: UIScrollView, from: CGFloat) {
            let innerTop = -inner.adjustedContentInset.top
            let delta = inner.contentOffset.y - from
            let inset = outer.adjustedContentInset
            let lowest = -inset.top
            let highest = max(lowest, outer.contentSize.height + inset.bottom - outer.bounds.height)
            if delta < 0, inner.contentOffset.y < innerTop, outer.contentOffset.y > lowest {
                let pull = innerTop - inner.contentOffset.y
                outer.contentOffset.y = max(lowest, outer.contentOffset.y - pull)
                inner.contentOffset.y = innerTop
            } else if delta > 0, outer.contentOffset.y < highest {
                let take = min(delta, highest - outer.contentOffset.y)
                outer.contentOffset.y += take
                inner.contentOffset.y -= take
            }
        }

        /// Scrolls the editor's scroll view so the caret is visible (a growing body; a scrolling one follows its own
        /// caret), with [bodyToTop] first so the body starts at the top of it. Calls in one turn are one scroll, on
        /// the next turn once SwiftUI laid out the height, tried again a moment later while the scroll view has not
        /// yet taken the height that leaves room for the body. Without [needsFocus] it also runs for a body that is
        /// not being edited (back from the preview).
        func reveal(bodyToTop: Bool, needsFocus: Bool = true) {
            pendingToTop = pendingToTop || bodyToTop
            pendingNeedsFocus = pendingNeedsFocus && needsFocus
            guard !pending else { return }
            pending = true
            DispatchQueue.main.async { [weak self] in self?.runReveal(tries: 0) }
        }

        private func runReveal(tries: Int) {
            let toTop = pendingToTop
            let needsFocus = pendingNeedsFocus
            pending = false
            pendingToTop = false
            pendingNeedsFocus = true
            guard let view, !needsFocus || view.isFirstResponder else { return }
            guard let scroll = Self.scrollView(around: view), view.window != nil, view.bounds.height > 0 else {
                // a new view (back from the preview) that is not in its scroll view yet: again in a moment
                if !needsFocus, tries < 8 {
                    pending = true
                    pendingNeedsFocus = false
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in self?.runReveal(tries: tries + 1) }
                }
                return
            }
            scroll.layoutIfNeeded()
            let inset = scroll.adjustedContentInset
            let lowest = -inset.top
            let highest = max(lowest, scroll.contentSize.height + inset.bottom - scroll.bounds.height)
            let shown = scroll.bounds.height - inset.top - inset.bottom
            var y = scroll.contentOffset.y
            if toTop, !movedUp {
                let top = view.convert(CGPoint.zero, to: scroll).y - inset.top
                if top > highest + 0.5, tries < 8 {
                    // not laid out with the keyboard's room yet: again in a moment
                    pending = true
                    pendingToTop = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in self?.runReveal(tries: tries + 1) }
                } else {
                    movedUp = true
                }
                y = max(y, top)
            }
            if view.isScrollEnabled {
                if !needsFocus { view.scrollRangeToVisible(view.selectedRange) }
            } else if let end = view.selectedTextRange?.end {
                let caret = view.convert(view.caretRect(for: end), to: scroll).insetBy(dx: 0, dy: -8)
                if caret.maxY > y + inset.top + shown { y = caret.maxY - inset.top - shown }
                if caret.minY < y + inset.top { y = caret.minY - inset.top }
            }
            y = min(max(y, lowest), highest)
            if abs(y - scroll.contentOffset.y) > 0.5 { scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: y), animated: false) }
            if toTop, movedUp { movedTo = scroll.contentOffset.y }
            if !needsFocus, tries == 0 {
                // a new view's first layout may not have its full height yet: once more when it has
                pending = true
                pendingNeedsFocus = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.runReveal(tries: 1) }
            }
        }

        private static func scrollView(around view: UIView) -> UIScrollView? {
            var next = view.superview
            while let candidate = next {
                if let scroll = candidate as? UIScrollView { return scroll }
                next = candidate.superview
            }
            return nil
        }
    }
}

private enum EditorSheet: String, Identifiable {
    case link, snippets, poll, options, community, uploads, photos, addPhotos, camera, translate, aiAssist, aiImage, dictation, video
    var id: String { rawValue }
}

/// `moment(date).format()`: local time with its offset (what RN sends as a schedule).
func scheduleString(_ date: Date, zone: TimeZone = .current) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = zone
    f.dateFormat = "yyyy-MM-dd'T'HH:mm:ssxxx"
    return f.string(from: date)
}

/// `Date.toISOString()`.
func isoString(_ date: Date) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone(identifier: "UTC")
    f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    return f.string(from: date)
}

private func date(iso: String) -> Date? {
    EditorMeta.shared.isoMillis(iso: iso).map { Date(timeIntervalSince1970: Double(truncating: $0) / 1000) }
}

/// The tag field, a UITextField that applies the model's answer as soon as UIKit applied the keystroke. A SwiftUI
/// TextField bound to the model's `tagInput` never showed the model clearing a typed tag (the value changed inside
/// the field's own setter, and a @State copy fared no better): "a " stayed in the field after it became a chip, and
/// the next keystroke sent it to be parsed again ("Tag exists already").
///
/// UIKit applies every edit itself (any range, composed characters and emoji included); `editingChanged` then hands
/// the result to the model, and when the model kept less (a chip was made, the text was lowercased) the field takes
/// the model's text with the caret kept the same distance from the end (the chip's text leaves from the front).
struct TagField: UIViewRepresentable {
    let model: EditorModel
    /// The model's text: SwiftUI updates the view when it changes (a cleared editor, a loaded draft).
    let modelText: String
    let textColor: UIColor
    let enabled: Bool

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> UITextField {
        let field = UITextField()
        // the hint is a SwiftUI Text behind the field (it wraps); VoiceOver reads it as the field's label
        field.accessibilityLabel = EditorUiCopy.shared.TAGS_SEPARATE_WITH_SPACE
        field.contentVerticalAlignment = .top
        field.font = UIFont.preferredFont(forTextStyle: .subheadline)
        field.adjustsFontForContentSizeCategory = true
        field.autocapitalizationType = .none
        field.autocorrectionType = .no
        field.spellCheckingType = .no
        field.smartQuotesType = .no
        field.smartDashesType = .no
        field.smartInsertDeleteType = .no
        field.keyboardType = .asciiCapable
        field.returnKeyType = .done
        field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        field.accessibilityIdentifier = "tag-input"
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        field.text = model.current.tagInput
        return field
    }

    /// Fills what it is offered: the hint behind it decides the height, so a wrapped hint is all tappable.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView field: UITextField, context _: Context) -> CGSize? {
        let natural = field.intrinsicContentSize
        return CGSize(width: proposal.width ?? natural.width, height: max(proposal.height ?? natural.height, natural.height))
    }

    func updateUIView(_ field: UITextField, context: Context) {
        context.coordinator.model = model
        field.textColor = textColor
        field.isEnabled = enabled
        context.coordinator.update(field, snapshot: modelText)
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var model: EditorModel
        init(model: EditorModel) { self.model = model }

        /// A SwiftUI update: the field shows the model's current text, never [snapshot], the text SwiftUI rendered
        /// with (it can be a keystroke behind and would put back text that already became a chip).
        func update(_ field: UITextField, snapshot _: String) {
            show(model.current.tagInput, in: field)
        }

        @objc func changed(_ field: UITextField) {
            // an input method still composing: wait for the committed text
            guard field.markedTextRange == nil else { return }
            model.setTagInput(text: field.text ?? "")
            show(model.current.tagInput, in: field)
        }

        /// Puts [text] in the field (keeping the caret's distance from the end) and tells the model it shows.
        func show(_ text: String, in field: UITextField) {
            guard field.markedTextRange == nil else { return }
            if (field.text ?? "") != text {
                let fromEnd = field.selectedTextRange.map { field.offset(from: $0.end, to: field.endOfDocument) } ?? 0
                field.text = text
                if let caret = field.position(from: field.endOfDocument, offset: -min(fromEnd, (text as NSString).length)) {
                    field.selectedTextRange = field.textRange(from: caret, to: caret)
                }
            }
            model.tagFieldShown(text: text)
        }

        // RN onEndEditing: a fragment longer than one character becomes a tag when the field loses focus
        func textFieldDidEndEditing(_ field: UITextField) {
            model.endTagEditing()
            show(model.current.tagInput, in: field)
        }

        func textFieldShouldReturn(_ field: UITextField) -> Bool {
            field.resignFirstResponder()
            return true
        }
    }
}

struct EditorScreen: View {
    @StateObject private var editor: Observed<EditorState>
    @StateObject private var uploads: Observed<UploadsState>
    @StateObject private var video: Observed<VideoUploadState>
    @State private var videoNotice: String?
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.scenePhase) private var scenePhase
    @State private var sheet: EditorSheet?
    @State private var showFormats = false
    /// Goes up when the preview closes: the body that comes back shows its caret again.
    @State private var bodyReveal = 0
    @State private var confirmSubmit = false
    @State private var confirmDiscard = false
    @State private var confirmClear = false
    @State private var saveChoice = false
    @State private var draftPrompt: EditorDonePublished?
    @State private var importingFiles = false
    @State private var mentions: [String] = []
    @State private var mentionTask: Task<Void, Never>?
    @State private var handledDone = false
    private let env: AppEnvironment

    init(args: EditorArgs, env: AppEnvironment) {
        self.env = env
        let model = env.editorModel(args: args)
        _editor = StateObject(wrappedValue: Observed(model, ownsModel: true))
        _uploads = StateObject(wrappedValue: Observed(env.uploadsModel(editor: model), ownsModel: true))
        _video = StateObject(wrappedValue: Observed(env.videoUploadModel(editor: model), ownsModel: true))
    }

    private var model: EditorModel { editor.model as! EditorModel }
    private var uploader: UploadsModel { uploads.model as! UploadsModel }

    private func pop() { app.path = Array(app.path.dropLast()) }

    private func replace(_ screen: AppScreen) {
        pop()
        app.push(screen)
    }

    private func leave() {
        if editor.state.isSending { return }
        if model.leavingLosesChanges {
            confirmDiscard = true
        } else {
            model.leave()
            pop()
        }
    }

    private func upload(_ images: [PickedImage], insert: Bool = true) {
        if images.isEmpty {
            env.ui.toast(text: UiCopy.shared.INVALID_IMAGE)
        } else {
            uploader.upload(files: images, insert: insert)
        }
    }

    var body: some View {
        sheets(alerts(content(editor.state), editor.state), editor.state)
            .task(id: editor.state.loading) { await takeShared() }
    }

    /// RN hasSharedIntent: content shared into the app lands in a new post once it loaded (text at the caret,
    /// images prepared like picked ones and uploaded).
    private func takeShared() async {
        let state = editor.state
        guard !state.loading, model.args.kind == EditorKind.theNewPost, let shared = env.share.takeForEditor(owner: state.owner) else { return }
        if !shared.text.isEmpty { model.insertSnippet(text: shared.text) }
        var images: [PickedImage] = []
        for (index, handle) in shared.images.enumerated() {
            if let image = await ShareInbox.prepare(handle, index: index) { images.append(image) }
        }
        if !shared.images.isEmpty { upload(images) }
    }

    private func content(_ state: EditorState) -> some View {
        VStack(spacing: 0) {
            if state.localSaveFailed {
                Text(EditorUiCopy.shared.COULD_NOT_STORE_THE)
                    .font(.footnote).foregroundStyle(theme.dangerText).padding(.horizontal, 16).accessibilityIdentifier("local-save-failed")
            }
            if state.loading {
                PageLoading().frame(maxHeight: .infinity, alignment: .top)
            } else if let error = state.error, state.owner != nil, state.body.isEmpty, state.kind.name != "NEW_POST" {
                VStack(spacing: 12) {
                    Text(error.message).foregroundStyle(theme.secondary)
                    Button(EditorLoadCopy.shared.RETRY) { model.retryLoad() }.accessibilityIdentifier("editor-retry")
                    Button(UiCopy.shared.BACK) { pop() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity).accessibilityIdentifier("editor-error")
            } else if state.isPreview {
                preview(state)
            } else {
                fields(state)
            }
        }
        .background(theme.background)
        .blocksBack(true)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar(state) }
        .onAppear { model.start() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { model.flush() } }
        .onChange(of: state.isPreview) { was, now in if was, !now { bodyReveal += 1 } }
        .onReceive(editor.$state) { done($0.done) }
        .onReceive(uploads.$state) { _ in }
    }

    private func alerts<V: View>(_ view: V, _ state: EditorState) -> some View {
        view
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { uploads.state.alert != nil }, set: { if !$0 { uploader.clearAlert() } })) {
            Button(UiCopy.shared.OK) { uploader.clearAlert() }
        } message: { Text(uploads.state.alert ?? "") }
        .alert(state.isEdit ? EditorUiCopy.shared.PUBLISHING_EDITS : EditorUiCopy.shared.PUBLISHING_NEW_POST, isPresented: $confirmSubmit) {
            Button(EditorUiCopy.shared.NO_BUTTON, role: .cancel) {}
            Button(EditorUiCopy.shared.YES_BUTTON) { model.submit() }
        } message: { Text(EditorUiCopy.shared.ARE_YOU_SURE) }
        .alert(EditorUiCopy.shared.DISCARD_CHANGES, isPresented: $confirmDiscard) {
            Button(EditorUiCopy.shared.KEEP_EDITING, role: .cancel) {}
            Button(EditorUiCopy.shared.DISCARD, role: .destructive) { model.leave(); pop() }
        } message: { Text(EditorUiCopy.shared.YOUR_EDITS_WERE_NOT) }
        .alert(UiCopy.shared.CLEAR, isPresented: $confirmClear) {
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
            Button(UiCopy.shared.CLEAR, role: .destructive) { model.clear() }
        } message: { Text(EditorUiCopy.shared.CLEAR_CONFIRM) }
    }

    private func sheets<V: View>(_ view: V, _ state: EditorState) -> some View {
        view
        .confirmationDialog(
            state.conflictChangedElsewhere ? EditorUiCopy.shared.THIS_LINK_IS_ALREADY : state.isReply ? UiCopy.shared.YOUR_REPLY_WAS_ALREADY : EditorUiCopy.shared.YOUR_POST_WAS_ALREADY,
            isPresented: Binding(get: { editor.state.publishConflict }, set: { if !$0 { model.dismissPublishConflict() } }), titleVisibility: .visible
        ) {
            // an earlier attempt is on chain and the text changed since: never edited silently
            Button(UiCopy.shared.UPDATE_IT) { model.resolvePublishConflict(update: true) }
            Button(state.isReply ? UiCopy.shared.NEW_REPLY : EditorUiCopy.shared.NEW_POST) { model.resolvePublishConflict(update: false) }
            Button(UiCopy.shared.CANCEL, role: .cancel) { model.dismissPublishConflict() }
        } message: {
            Text(state.conflictChangedElsewhere ? EditorUiCopy.shared.A_POST_ALREADY_EXISTS
                : state.isReply ? UiCopy.shared.UPDATE_IT_OR_POST : EditorUiCopy.shared.UPDATE_IT_OR_PUBLISH)
        }
        .postingAuthorityPrompt(isPresented: editor.state.authorityPrompt, onAnswer: { model.answerAuthorityPrompt(grant: $0) }, onDismiss: { model.dismissAuthorityPrompt() })
        .alert(EditorUiCopy.shared.ALLOW_ECENCY_TO_PUBLISH, isPresented: Binding(get: { editor.state.grantPrompt }, set: { if !$0 { model.dismissPostingGrant() } })) {
            // scheduling changes the account's posting authority: only after an explicit yes, then the active key is asked
            Button(UiCopy.shared.CANCEL, role: .cancel) { model.dismissPostingGrant() }
            Button(EditorUiCopy.shared.GRANT) { model.confirmPostingGrant() }
        } message: { Text(EditorUiCopy.shared.A_SCHEDULED_POST_IS) }
        .alert(EditorUiCopy.shared.RESTORE_UNSAVED_CHANGES, isPresented: Binding(get: { editor.state.recoveryAvailable }, set: { _ in })) {
            Button(EditorUiCopy.shared.DISCARD, role: .destructive) { model.discardRecovery() }
            Button(EditorUiCopy.shared.RESTORE) { model.restoreRecovery() }
        } message: { Text(EditorUiCopy.shared.YOU_HAVE_UNSAVED_CHANGES) }
        .confirmationDialog(EditorUiCopy.shared.SAVING_DRAFT, isPresented: $saveChoice, titleVisibility: .visible) {
            Button(EditorUiCopy.shared.UPDATE_CURRENT_DRAFT) { model.saveDraft(saveAsNew: false) }
            Button(EditorUiCopy.shared.SAVE_AS_NEW_DRAFT) { model.saveDraft(saveAsNew: true) }
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
        }
        .alert(EditorUiCopy.shared.POST_PUBLISHED, isPresented: Binding(get: { draftPrompt != nil }, set: { _ in })) {
            Button(EditorUiCopy.shared.KEEP_DRAFT, role: .cancel) { finishPublished() }
            Button(UiCopy.shared.DELETE, role: .destructive) { model.deletePublishedDraft(); finishPublished() }
        } message: { Text(EditorUiCopy.shared.PUBLISHED_DRAFT_DELETE_BODY) }
        .alert(EditorUiCopy.shared.NOTICE, isPresented: Binding(get: { videoNotice != nil }, set: { if !$0 { videoNotice = nil } })) {
            Button(UiCopy.shared.OK, role: .cancel) { videoNotice = nil }
        } message: { Text(videoNotice ?? "") }
        .themedSheet(item: $sheet) { which in sheetView(which, state: editor.state) }
        #if DEBUG
        // -editorSheet aiAssist | aiImage | dictation | translate | video (screenshots of fake-data runs)
        .task {
            guard let name = UserDefaults.standard.string(forKey: "editorSheet"), let which = EditorSheet(rawValue: name) else { return }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if sheet == nil { sheet = which }
        }
        #endif
        // "Choose from Files": the shared importer (allowed image types only, prepared without metadata)
        .mediaFileImporter(surface: filesSurface, onImages: onFilesPicked)
    }

    /// The Files importer's surface: the editor's while "Choose from Files" is open.
    private var filesSurface: Binding<MediaSurface?> {
        Binding(get: { importingFiles ? MediaSurface.editor : nil }, set: { importingFiles = $0 != nil })
    }

    private func onFilesPicked(_: MediaSurface, _ images: [PickedImage]) { upload(images) }

    private func finishPublished() {
        guard let done = draftPrompt else { return }
        draftPrompt = nil
        replace(AppScreenProfile(author: done.author))
    }

    private func done(_ done: EditorDone?) {
        guard let done, !handledDone else { return }
        handledDone = true
        switch done {
        case let published as EditorDonePublished:
            if published.draftId != nil {
                draftPrompt = published
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { replace(AppScreenProfile(author: published.author)) }
            }
        case is EditorDoneScheduled: replace(AppScreenDrafts(tab: 1))
        default: pop()
        }
    }

    @ToolbarContentBuilder
    private func toolbar(_ state: EditorState) -> some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            Button { leave() } label: { Image(appIcon: .back) }.accessibilityLabel(UiCopy.shared.BACK).accessibilityIdentifier("editor-back")
        }
        if !state.isReply, !state.isEdit {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(state.isDraftSaved ? EditorUiCopy.shared.SAVED : UiCopy.shared.SAVE) { if state.draftId != nil { saveChoice = true } else { model.saveDraft(saveAsNew: false) } }
                    .disabled(state.isDraftSaved || state.isDraftSaving).accessibilityIdentifier("save-draft")
            }
            .onPage()
        }
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            Button { model.setPreview(on: !state.isPreview) } label: { Image(appIcon: state.isPreview ? .edit : .preview) }
                .accessibilityLabel(state.isPreview ? UiCopy.shared.EDIT : EditorUiCopy.shared.PREVIEW).accessibilityIdentifier("preview-toggle")
            if !state.isReply {
                Button { sheet = .options } label: { Image(appIcon: .settings) }.accessibilityLabel(EditorUiCopy.shared.POST_OPTIONS).accessibilityIdentifier("post-options")
            }
            if state.isSending || state.uploading > 0 {
                ProgressView().accessibilityIdentifier("editor-busy")
            } else {
                Button(state.submitLabel) { if state.kind == .reply { model.submit() } else { confirmSubmit = true } }
                    .bold().disabled(!state.canSubmit).accessibilityIdentifier("editor-submit")
            }
        }
    }

    private func preview(_ state: EditorState) -> some View {
        GeometryReader { geo in
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if !state.isReply {
                        Text(state.title).font(.title2.bold()).foregroundStyle(theme.text)
                        if !state.tags.isEmpty { Text(state.tags.map { "#\($0)" }.joined(separator: "  ")).font(.footnote).foregroundStyle(theme.accentText) }
                    }
                    if let blocks = state.preview {
                        PostBodyView(blocks: blocks, contentWidth: max(geo.size.width - 32, 100))
                    } else {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 24)
                    }
                    if let poll = state.poll, !poll.title.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(poll.title).bold().foregroundStyle(theme.text)
                            ForEach(Array(poll.choices.enumerated()), id: \.offset) { _, choice in
                                Text(choice).frame(maxWidth: .infinity, alignment: .leading).padding(10)
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border))
                            }
                        }
                        .padding(12).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12)).accessibilityIdentifier("poll-preview")
                    }
                }
                .padding(16)
            }
        }
        .accessibilityIdentifier("editor-preview")
    }

    private func fields(_ state: EditorState) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // one scroll container for the header and the body: the community row, title and tags scroll away with
            // the text, and the body is at least as tall as what is visible, so with the keyboard up it gets the room
            GeometryReader { geo in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        header(state)
                        bodyField(state, minHeight: geo.size.height)
                    }
                }
                .scrollDismissesKeyboard(.never)
            }
            footer(state)
        }
    }

    @ViewBuilder
    private func header(_ state: EditorState) -> some View {
        if !state.isReply, !state.isEdit {
            Button { sheet = .community } label: {
                HStack {
                    AvatarView(url: "https://images.ecency.com/u/\(state.community?.name ?? state.owner ?? "")/avatar/small", size: 28)
                    Text(state.community?.title ?? EditorUiCopy.shared.MY_BLOG).foregroundStyle(theme.text).accessibilityIdentifier("community-row-title")
                    Image(appIcon: .expand).font(.footnote).foregroundStyle(theme.icon).accessibilityHidden(true)
                    Spacer()
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8).accessibilityIdentifier("community-row")
            if !state.canPostToCommunity {
                Text(EditorUiCopy.shared.communityRestriction(title: state.community?.title ?? "")).font(.footnote).foregroundStyle(theme.dangerText)
                    .padding(.horizontal, 16).accessibilityIdentifier("community-restriction")
            }
        }
        if !state.isReply {
            TextField(EditorUiCopy.shared.TITLE, text: Binding(get: { editor.state.title }, set: { model.setTitle(text: $0) }), axis: .vertical)
                .font(.title2.bold()).padding(.horizontal, 16).padding(.vertical, 8).accessibilityIdentifier("editor-title")
            // chips wrap onto more lines and the field takes its own full-width line, its hint a wrapping Text:
            // a UITextField placeholder is one line and clipped long languages (de, ru) and large text
            VStack(alignment: .leading, spacing: 6) {
                if !state.tags.isEmpty {
                    ChipFlow(spacing: 6) {
                        ForEach(state.tags, id: \.self) { tag in
                            Button { model.removeTag(tag: tag) } label: {
                                Text(tag).font(.footnote).foregroundStyle(.white).padding(.horizontal, 10).padding(.vertical, 4)
                                    .background(theme.accent, in: Capsule())
                            }
                            .accessibilityHint(L.Native.Ui.remove)
                            .accessibilityIdentifier("tag-\(tag)")
                        }
                    }
                }
                // the hint sets the height (one line once something is typed); the field covers all of it
                Text(editor.state.tagInput.isEmpty ? EditorUiCopy.shared.TAGS_SEPARATE_WITH_SPACE : " ")
                    .font(.subheadline).foregroundStyle(theme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .topLeading)
                    .accessibilityHidden(true)
                    .overlay {
                        TagField(model: model, modelText: editor.state.tagInput, textColor: UIColor(theme.text), enabled: !state.isSending)
                    }
            }
            .padding(.horizontal, 16)
            if let warning = state.tagWarning {
                Text(warning).font(.caption).foregroundStyle(theme.dangerText).padding(.horizontal, 16).padding(.top, 2).announces(warning, debounce: true).accessibilityIdentifier("tag-warning")
            }
        } else {
            if let summary = state.parentSummary, !state.isEdit {
                Text(summary).font(.footnote).foregroundStyle(theme.secondary).lineLimit(3).padding(.horizontal, 16).padding(.vertical, 8).accessibilityIdentifier("parent-summary")
            }
            if state.isEdit {
                Text("@\(state.owner ?? "")").bold().foregroundStyle(theme.text).padding(.horizontal, 16).padding(.vertical, 4)
            } else {
                // RN reply account tile: avatar, @name and the arrow; a tap opens the accounts sheet and the reply carries over
                Button {
                    app.env.replyHandoff.begin(editor: model)
                    app.env.replyHandoff.sheetShown()
                    app.sheet = .accounts
                } label: {
                    HStack(spacing: 8) {
                        AvatarView(url: PostCard.companion.avatarUrl(account: state.owner ?? "", proxyBase: app.env.proxyBase, size: "small"), size: 28)
                        Text("@\(state.owner ?? "")").bold().foregroundStyle(theme.text)
                        Image(appIcon: .expand).scaledFont(12, weight: .semibold).foregroundStyle(theme.text)
                    }
                }
                .buttonStyle(.plain)
                .disabled(state.isSending)
                .padding(.horizontal, 16).padding(.vertical, 4)
                .accessibilityLabel(ReplyAccountCopy.shared.SWITCH)
                .accessibilityIdentifier("reply-account-tile")
            }
        }
    }

    private func bodyField(_ state: EditorState, minHeight: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            BodyTextView(
                text: state.body, selection: state.selection, revision: state.bodyRevision, editable: !state.isSending,
                placeholder: state.isReply ? EditorUiCopy.shared.ADD_YOUR_REPLY : EditorUiCopy.shared.TELL_US_SOMETHING,
                minHeight: minHeight, revealToken: bodyReveal
            ) { text, range in
                model.setBody(text: text, selection: CoreTextSelection(start: Int32(range.location), end: Int32(range.location + range.length)))
                lookUpMentions()
            }
            if state.body.isEmpty {
                Text(state.isReply ? EditorUiCopy.shared.ADD_YOUR_REPLY : EditorUiCopy.shared.TELL_US_SOMETHING).foregroundStyle(theme.secondary).padding(.horizontal, 17).padding(.vertical, 8).allowsHitTesting(false)
                    // the body view reads its own placeholder to VoiceOver
                    .accessibilityHidden(true)
            }
        }
    }

    /// What stays under the scrolling text, above the keyboard: counts, chips, mentions, progress and the toolbar.
    @ViewBuilder
    private func footer(_ state: EditorState) -> some View {
        if state.wordsCount > 0 || state.questChip != nil {
            // one row (owner 11-04): the quest chip at the leading edge, the words count at the trailing edge; the
            // chip's text truncates before the row takes a second line. RN shows the count in the header; the iOS
            // bar has no room for it next to the buttons
            HStack(spacing: 8) {
                if let chip = state.questChip {
                    // RN quest chip: opens Perks; the x closes it for this run of the app
                    HStack(spacing: 4) {
                        Button { app.push(AppScreenCompanion.shared.of(route: .perks, arg: "")) } label: {
                            Text(chip).font(.footnote).foregroundStyle(theme.text).lineLimit(1).truncationMode(.tail).frame(minHeight: minTapTarget)
                        }
                        Button { model.dismissQuestChip() } label: { Image(appIcon: .close).font(.caption2).tapTarget() }.accessibilityLabel(UiCopy.shared.CANCEL).accessibilityIdentifier("quest-chip-close")
                    }
                    .padding(.horizontal, 12).background(theme.lightBackground, in: Capsule())
                    .accessibilityIdentifier("quest-chip")
                }
                Spacer(minLength: 0)
                if state.wordsCount > 0 {
                    Text(L.Native.Ios.Editor.words(count: Int(state.wordsCount))).font(.caption).foregroundStyle(theme.secondary)
                        .lineLimit(1).fixedSize().accessibilityIdentifier("words-count")
                }
            }
            .padding(.horizontal, 16).padding(.vertical, state.questChip == nil ? 0 : 4)
        }
        if state.showsShortReplyHint {
            Text(Quests.shared.SHORT_REPLY_HINT).font(.caption).foregroundStyle(theme.secondary).padding(.horizontal, 16).accessibilityIdentifier("short-reply-hint")
        }
        if state.recentDraftAvailable {
            HStack {
                Spacer()
                Button { model.loadRecentDraft() } label: { Label(EditorUiCopy.shared.DRAFT, appIcon: .compose).bold().foregroundStyle(.white).padding(.horizontal, 16).padding(.vertical, 8).background(theme.accent, in: Capsule()) }
                    .accessibilityIdentifier("load-draft")
            }
            .padding(.horizontal, 16).padding(.bottom, 8)
        }
        if !mentions.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(mentions, id: \.self) { name in
                        Button { model.insertUsername(username: name); mentions = [] } label: {
                            HStack(spacing: 6) {
                                AvatarView(url: "https://images.ecency.com/u/\(name)/avatar/small", size: 20)
                                Text(name).font(.footnote).foregroundStyle(theme.text)
                            }
                            .padding(.horizontal, 8).padding(.vertical, 4).background(theme.lightBackground, in: Capsule())
                        }
                        .accessibilityIdentifier("mention-\(name)")
                    }
                }
                .padding(.horizontal, 8)
            }
            .accessibilityIdentifier("mention-bar")
        }
        if let progress = uploads.state.progress {
            HStack {
                ProgressView(value: Double(truncating: progress)).tint(theme.accentText)
                Button(UiCopy.shared.CANCEL, role: .destructive) { uploader.cancelUploads() }.accessibilityIdentifier("cancel-upload")
            }
            .padding(.horizontal, 16).padding(.vertical, 4).accessibilityIdentifier("upload-progress")
        }
        if showFormats {
            HStack(spacing: 4) {
                toolButton("B", id: "format-bold", label: EditorUiCopy.shared.BOLD) { model.bold() }.bold()
                toolButton("I", id: "format-italic", label: EditorUiCopy.shared.ITALIC) { model.italic() }.italic()
                toolButton(EditorUiCopy.shared.TABLE, id: "format-table", label: EditorUiCopy.shared.TABLE) { model.table() }
                toolButton("[ ]( )", id: "format-link", label: EditorUiCopy.shared.LINK) { model.wrapLink() }
                Spacer()
            }
            .padding(.horizontal, 8).background(theme.lightBackground)
        }
        if state.rcWarning {
            // RN RcPrecheckBanner: advice while writing, never a block; a tap opens the top up / boost offer
            Button { model.showRcOffer() } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(EditorModel.companion.RC_PRECHECK_TITLE).font(.footnote.bold()).foregroundStyle(theme.text)
                        Text(EditorModel.companion.RC_PRECHECK_BODY).font(.caption).foregroundStyle(theme.secondary)
                    }
                    Spacer()
                    Text(RcErrors.shared.TOP_UP).font(.caption.bold()).foregroundStyle(theme.accentText)
                }
                .padding(12).background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .padding(.horizontal, 16).accessibilityIdentifier("rc-precheck")
        }
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                // owner order (10-28): the writing tools first, AI Assist on screen without scrolling (it holds the
                // image generator and dictation), the rarely used poll late
                toolButton("Aa", id: "tool-format", label: EditorUiCopy.shared.TEXT_FORMAT, selected: showFormats) { showFormats.toggle() }
                toolButton(UiCopy.shared.IMAGE, id: "tool-image", label: UiCopy.shared.IMAGE) { sheet = .uploads }
                toolButton(UiCopy.shared.VIDEO, id: "tool-video", label: EditorUiCopy.shared.ADD_VIDEO) {
                    // RN _showVideoUploads: one 3Speak video a post
                    if let why = (video.model as! VideoUploadModel).refusal() {
                        videoNotice = why
                    } else {
                        (video.model as! VideoUploadModel).reset() // the video of an earlier upload was taken out of the body again
                        sheet = .video
                    }
                }
                toolButton(EditorUiCopy.shared.AI_ASSIST, id: "tool-ai-assist", label: EditorUiCopy.shared.AI_WRITING_ASSISTANT) { sheet = .aiAssist }
                toolButton(EditorUiCopy.shared.SNIPPETS, id: "tool-snippets", label: EditorUiCopy.shared.SNIPPETS) { sheet = .snippets }
                toolButton(EditorUiCopy.shared.LINK, id: "tool-link", label: EditorUiCopy.shared.INSERT_LINK) { sheet = .link }
                if !state.isEdit, !state.isReply { toolButton(UiCopy.shared.POLL, id: "tool-poll", label: UiCopy.shared.POLL) { sheet = .poll } }
                toolButton(UiCopy.shared.TRANSLATE, id: "tool-translate", label: EditorUiCopy.shared.TRANSLATE_POST) { sheet = .translate }
                toolButton(UiCopy.shared.CLEAR, id: "tool-clear", label: UiCopy.shared.CLEAR) { confirmClear = true }
            }
            .padding(.horizontal, 8)
        }
        .accessibilityIdentifier("editor-toolbar")
    }

    /// A toolbar button: its text grows up to the first accessibility size, so the bar keeps room for the body; a
    /// long press shows it in the large content viewer beyond that.
    private func toolButton(_ title: String, id: String, label: String, selected: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline).foregroundStyle(selected ? theme.accentText : theme.secondary)
                .padding(.horizontal, 12).padding(.vertical, 10).frame(minHeight: minTapTarget)
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .largeContent(label)
        .accessibilityIdentifier(id)
    }

    /// RN usernameAutofillBar: accounts matching the "@word" at the caret, 300 ms after the last keystroke.
    private func lookUpMentions() {
        mentionTask?.cancel()
        guard let query = model.mentionQuery else {
            if !mentions.isEmpty { mentions = [] }
            return
        }
        mentionTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            let names = (try? await env.communityApi.lookupAccounts(prefix: query, limit: 5)) ?? []
            if !Task.isCancelled { mentions = names }
        }
    }

    @ViewBuilder
    private func sheetView(_ which: EditorSheet, state: EditorState) -> some View {
        switch which {
        case .link: LinkSheet(state: state) { label, url, type in model.insertLink(label: label, url: url, type: type) }
        case .snippets: SnippetsSheet(env: env) { text in model.insertSnippet(text: text); sheet = nil }
        case .poll: PollSheet(current: state.poll, onAttach: { model.setPoll(poll: $0); sheet = nil }, onReset: { model.setPoll(poll: nil) })
        case .options: OptionsSheet(editor: editor, env: env)
        case .community: CommunitySheet(env: env) { choice in model.selectCommunity(community: choice); sheet = nil }
        case .uploads:
            UploadsSheet(
                uploads: uploads,
                onGallery: { sheet = .photos },
                onFiles: { sheet = nil; DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { importingFiles = true } },
                onCamera: { sheet = .camera },
                onPaste: {
                    sheet = nil
                    guard let image = UIPasteboard.general.image else { env.ui.toast(text: EditorUiCopy.shared.CLIPBOARD_NO_IMAGE); return }
                    Task.detached(priority: .userInitiated) {
                        let picked = image.jpegData(compressionQuality: 0.95).flatMap { EditorImages.prepare(data: $0, name: nil, index: 0) }
                        await MainActor.run { upload(picked.map { [$0] } ?? []) }
                    }
                },
                onInsert: { urls in uploader.insert(urls: urls); sheet = nil },
                onAdd: { sheet = .addPhotos }
            )
        case .photos: PhotoPicker { images in sheet = nil; upload(images) }.ignoresSafeArea()
        // RN uploads gallery "+": the picked images only join the gallery, and the gallery comes back to show them
        case .addPhotos: PhotoPicker { images in sheet = .uploads; upload(images, insert: false) }.ignoresSafeArea()
        case .translate: ComposeTranslateSheet(editor: model, env: env, language: app.readerLanguage)
        // the AI hub: the image generator and dictation take its place, each as it opened from the toolbar before
        case .aiAssist: AiAssistSheet(editor: model, env: env, onImage: { sheet = .aiImage }, onDictate: { sheet = .dictation })
        case .dictation: DictationSheet(editor: model, env: env)
        case .video: VideoUploadSheet(video: video)
        case .aiImage: AiImageSheet(editor: model, env: env)
        case .camera: CameraPicker { images in sheet = nil; upload(images) }.ignoresSafeArea()
        }
    }
}

/// RN insertLinkModal.
private struct LinkSheet: View {
    let onInsert: (String, String, EditorFormats.LinkType) -> Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @State private var label: String
    @State private var url: String
    @State private var type = 0
    @State private var invalid = false

    init(state: EditorState, onInsert: @escaping (String, String, EditorFormats.LinkType) -> Bool) {
        self.onInsert = onInsert
        let body = state.body as NSString
        let start = min(max(Int(state.selection.start), 0), body.length)
        let end = min(max(Int(state.selection.end), start), body.length)
        let selected = body.substring(with: NSRange(location: start, length: end - start))
        let isLink = EditorFormats.shared.isWebLink(text: selected)
        _label = State(initialValue: isLink ? "" : selected)
        _url = State(initialValue: isLink ? selected : "")
    }

    var body: some View {
        NavigationStack {
            ThemedForm {
                Picker(L.Editor.linkTypeText, selection: $type) {
                    Text(EditorUiCopy.shared.PLAIN).tag(0)
                    Text(UiCopy.shared.VIDEO).tag(1)
                    Text(UiCopy.shared.IMAGE).tag(2)
                }
                .pickerStyle(.segmented).accessibilityIdentifier("link-type")
                TextField(EditorUiCopy.shared.ENTER_LABEL_OPTIONAL, text: $label).disabled(type != 0).accessibilityIdentifier("link-label")
                TextField(EditorUiCopy.shared.ENTER_URL, text: $url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().leftToRightField().accessibilityIdentifier("link-url")
                if invalid { Text(EditorUiCopy.shared.PLEASE_INSERT_VALID_URL).font(.footnote).foregroundStyle(theme.dangerText).announces(EditorUiCopy.shared.PLEASE_INSERT_VALID_URL, debounce: true).accessibilityIdentifier("link-invalid") }
            }
            .navigationTitle(EditorUiCopy.shared.INSERT_LINK).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CANCEL) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(EditorUiCopy.shared.INSERT_LINK) {
                        let kind: EditorFormats.LinkType = type == 2 ? .image : type == 1 ? .video : .plain
                        if onInsert(label, url.trimmingCharacters(in: .whitespaces), kind) { dismiss() } else { invalid = true }
                    }
                    .accessibilityIdentifier("link-insert")
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// RN snippetsModal.
private struct SnippetsSheet: View {
    @StateObject private var snippets: Observed<SnippetsState>
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @State private var editingId: String?
    @State private var editorShown = false
    @State private var title = ""
    @State private var text = ""
    @State private var incomplete = false
    @State private var deleting: String?

    init(env: AppEnvironment, onPick: @escaping (String) -> Void) {
        _snippets = StateObject(wrappedValue: Observed(env.snippetsModel(), ownsModel: true))
        self.onPick = onPick
    }

    private var model: SnippetsModel { snippets.model as! SnippetsModel }

    var body: some View {
        let state = snippets.state
        NavigationStack {
            List {
                if state.loaded, state.snippets.isEmpty { Text(EditorUiCopy.shared.NO_SNIPPETS_FOUND).foregroundStyle(theme.secondary).listStateRow().accessibilityIdentifier("snippets-empty") }
                ForEach(state.snippets, id: \.id) { snippet in
                    Button { onPick(snippet.body) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(snippet.title).bold().lineLimit(1).foregroundStyle(theme.text)
                            Text(snippet.body).font(.footnote).lineLimit(2).foregroundStyle(theme.secondary)
                        }
                    }
                    .themedCardRow()
                    .accessibilityIdentifier("snippet-\(snippet.title)")
                    .swipeActions {
                        if !snippet.id.isEmpty {
                            Button(UiCopy.shared.DELETE, role: .destructive) { deleting = snippet.id }
                            Button(UiCopy.shared.EDIT) { editingId = snippet.id; title = snippet.title; text = snippet.body; incomplete = false; editorShown = true }
                        }
                    }
                }
                if !state.endReached, state.loaded { Button(UiCopy.shared.LOAD_MORE) { model.loadMore() }.themedCardRow() }
            }
            .themedList()
            .overlay { if !state.loaded { ProgressView() } }
            .navigationTitle(EditorUiCopy.shared.SNIPPETS).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button { editingId = nil; title = ""; text = ""; incomplete = false; editorShown = true } label: { Label(L.Snippets.btnAdd, appIcon: .add) }.accessibilityIdentifier("snippet-add")
                }
            }
            .onAppear { model.start() }
            .themedSheet(isPresented: $editorShown) {
                NavigationStack {
                    ThemedForm {
                        TextField(EditorUiCopy.shared.SNIPPET_TITLE, text: $title, axis: .vertical).accessibilityIdentifier("snippet-title")
                        TextField(EditorUiCopy.shared.ADD_SNIPPET_BODY_HERE, text: $text, axis: .vertical).lineLimit(5...).accessibilityIdentifier("snippet-body")
                        if incomplete { Text(EditorUiCopy.shared.SNIPPET_INCOMPLETE).font(.footnote).foregroundStyle(theme.dangerText).announces(EditorUiCopy.shared.SNIPPET_INCOMPLETE, debounce: true) }
                    }
                    .navigationTitle(editingId == nil ? EditorUiCopy.shared.ADD_SNIPPET : EditorUiCopy.shared.EDIT_SNIPPET).navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE_CAPS) { editorShown = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(UiCopy.shared.SAVE_CAPS) { if model.save(id: editingId, title: String(title.prefix(250)), body: text) { editorShown = false } else { incomplete = true } }.accessibilityIdentifier("snippet-save")
                        }
                    }
                }
            }
            .alert(EditorUiCopy.shared.DELETE_SNIPPET, isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button(UiCopy.shared.CANCEL, role: .cancel) { deleting = nil }
                Button(UiCopy.shared.CONFIRM, role: .destructive) { if let id = deleting { model.delete(id: id) }; deleting = nil }
            } message: { Text(EditorUiCopy.shared.DELETE_SNIPPET_CONFIRM) }
        }
    }
}

/// RN pollsWizardContent (also the wave composer's poll button).
struct PollSheet: View {
    let current: PollDraft?
    let onAttach: (PollDraft) -> Void
    let onReset: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var choices: [String]
    @State private var end: Date
    @State private var age: Int
    @State private var maxChoices: Int
    @State private var tokens: Bool
    @State private var hideResults: Bool
    @State private var showVoters: Bool
    @State private var voteChange: Bool
    @State private var more = false

    init(current: PollDraft?, onAttach: @escaping (PollDraft) -> Void, onReset: @escaping () -> Void) {
        self.current = current
        self.onAttach = onAttach
        self.onReset = onReset
        _title = State(initialValue: current?.title ?? "")
        _choices = State(initialValue: current?.choices ?? ["", ""])
        _end = State(initialValue: current.flatMap { date(iso: $0.endTime) } ?? Date().addingTimeInterval(Double(PollDraft.companion.DEFAULT_DAYS) * 86400))
        _age = State(initialValue: Int(current?.accountAge ?? 100))
        _maxChoices = State(initialValue: Int(current?.maxChoicesVoted ?? 1))
        _tokens = State(initialValue: current?.interpretation == PollDraft.companion.TOKENS)
        _hideResults = State(initialValue: current?.hideResults ?? true)
        _showVoters = State(initialValue: !(current?.hideVotes ?? false))
        _voteChange = State(initialValue: current?.voteChange ?? false)
    }

    private var draft: PollDraft {
        PollDraft(
            title: title, choices: choices, endTime: isoString(end), voteChange: voteChange, hideVotes: !showVoters, hideResults: hideResults,
            interpretation: tokens ? PollDraft.companion.TOKENS : PollDraft.companion.NUMBER_OF_VOTES, maxChoicesVoted: Int32(maxChoices),
            token: tokens ? PollDraft.companion.HP_TOKEN : nil, communityMembership: current?.communityMembership ?? [], accountAge: Int32(age)
        )
    }

    var body: some View {
        NavigationStack {
            ThemedForm {
                Section(EditorUiCopy.shared.POLL_QUESTION) { TextField(EditorUiCopy.shared.ADD_POLL_QUESTION, text: $title, axis: .vertical).accessibilityIdentifier("poll-question") }
                Section(EditorUiCopy.shared.CHOICES) {
                    ForEach(choices.indices, id: \.self) { i in
                        HStack {
                            TextField(EditorUiCopy.shared.choice(number: Int32(i + 1)), text: Binding(get: { i < choices.count ? choices[i] : "" }, set: { if i < choices.count { choices[i] = $0 } })).accessibilityIdentifier("poll-choice-\(i)")
                            if i > 1 { Button { choices.remove(at: i) } label: { Image(appIcon: .clear) }.buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.REMOVE) }
                        }
                    }
                    Button(EditorUiCopy.shared.ADD_CHOICE) { choices.append("") }.accessibilityIdentifier("poll-add-choice")
                }
                Section { DatePicker(EditorUiCopy.shared.END_TIME, selection: $end, in: Date()...).accessibilityIdentifier("poll-end") }
                if more {
                    Section {
                        Stepper(L.Native.Ios.Editor.pollMinAge(days: Fmt.int(age)), value: $age, in: 0 ... 10000)
                        Stepper(L.Native.Ios.Editor.pollMaxOptions(count: Fmt.int(maxChoices)), value: $maxChoices, in: 0 ... 100)
                        Picker(EditorUiCopy.shared.INTERPRETATION, selection: $tokens) {
                            Text(EditorUiCopy.shared.NUMBER_OF_VOTES).tag(false)
                            Text(EditorUiCopy.shared.TOKEN_HIVE_POWER).tag(true)
                        }
                        Toggle(EditorUiCopy.shared.HIDE_RESULTS_UNTIL_VOTED, isOn: $hideResults)
                        Toggle(EditorUiCopy.shared.SHOW_VOTERS, isOn: $showVoters)
                        Toggle(EditorUiCopy.shared.ALLOW_VOTE_CHANGE, isOn: $voteChange)
                    }
                } else {
                    Button(EditorUiCopy.shared.SHOW_MORE_CONFIGURATION) { more = true }.accessibilityIdentifier("poll-more")
                }
                Section {
                    Button(current?.title.isEmpty == false ? EditorUiCopy.shared.UPDATE_POLL : EditorUiCopy.shared.ATTACH_POLL) { onAttach(draft) }.disabled(!draft.isValid).accessibilityIdentifier("poll-attach")
                    Button(EditorUiCopy.shared.RESET_POLL, role: .destructive) {
                        title = ""; choices = ["", ""]; end = Date().addingTimeInterval(Double(PollDraft.companion.DEFAULT_DAYS) * 86400)
                        age = 100; maxChoices = 1; tokens = false; hideResults = true; showVoters = true; voteChange = false
                        onReset()
                    }
                    .accessibilityIdentifier("poll-reset")
                }
            }
            .navigationTitle(EditorUiCopy.shared.CREATE_A_POLL).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { dismiss() } } }
        }
    }
}

/// RN postOptionsModal with its beneficiary section.
private struct OptionsSheet: View {
    @ObservedObject var editor: Observed<EditorState>
    let env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @State private var later = false
    @State private var when = Date().addingTimeInterval(3600)
    @State private var account = ""
    @State private var percent = ""
    @State private var benefError: String?
    @State private var templateShown = false
    @State private var templateName = ""

    private var model: EditorModel { editor.model as! EditorModel }

    var body: some View {
        let state = editor.state
        NavigationStack {
            ThemedForm {
                if !state.isEdit {
                    Section(EditorUiCopy.shared.SCHEDULED_FOR) {
                        Picker(EditorUiCopy.shared.SCHEDULED_FOR, selection: Binding(get: { later }, set: { later = $0; if !$0 { model.setSchedule(dateTime: nil) } })) {
                            Text(EditorUiCopy.shared.IMMEDIATE).tag(false)
                            Text(EditorUiCopy.shared.LATER).tag(true)
                        }
                        .pickerStyle(.segmented).accessibilityIdentifier("schedule-mode")
                        if later {
                            DatePicker(EditorUiCopy.shared.SCHEDULING_TIME, selection: Binding(get: { when }, set: { when = $0; model.setSchedule(dateTime: scheduleString($0)) }), in: Date()...)
                                .accessibilityIdentifier("schedule-pick")
                        }
                    }
                    Section(EditorUiCopy.shared.REWARD) {
                        ForEach(RewardType.entries, id: \.self) { reward in
                            Button { model.setReward(reward: reward) } label: {
                                HStack {
                                    Text(reward.label).foregroundStyle(theme.text)
                                    Spacer()
                                    if state.reward == reward { Image(appIcon: .check).foregroundStyle(theme.accentText) }
                                }
                            }
                            .accessibilityAddTraits(state.reward == reward ? .isSelected : [])
                            .accessibilityIdentifier("reward-\(reward.key)")
                        }
                        Toggle(EditorUiCopy.shared.SET_DEFAULT, isOn: Binding(get: { editor.state.isDefaultReward }, set: { model.setDefaultReward(on: $0) })).accessibilityIdentifier("reward-default")
                    }
                    if state.community != nil {
                        Toggle(EditorUiCopy.shared.REBLOG, isOn: Binding(get: { editor.state.reblog }, set: { model.setReblog(on: $0) })).accessibilityIdentifier("reblog-switch")
                    }
                    if !state.title.isEmpty || !state.body.isEmpty {
                        Button(EditorUiCopy.shared.SAVE_AS_TEMPLATE) { templateName = ""; templateShown = true }.accessibilityIdentifier("save-template")
                    }
                }
                Section(EditorUiCopy.shared.SELECT_POST_THUMBNAIL) {
                    let images = state.images
                    if images.count < 2 {
                        Text(EditorUiCopy.shared.ADD_MORE_IMGS).foregroundStyle(theme.secondary).accessibilityIdentifier("thumb-hint")
                    } else {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(images, id: \.self) { url in
                                    let selected = url == state.thumbUrl || (!images.contains(state.thumbUrl) && url == images.first)
                                    Button { model.setThumb(url: url) } label: {
                                        LoadedImageView(src: url, maxWidth: 72, contentMode: .scaleAspectFill) { _ in }
                                            .frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 8))
                                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? theme.accent : .clear, lineWidth: 3))
                                    }
                                    .buttonStyle(.plain).accessibilityLabel(selected ? L.Native.Ios.Editor.thumbnailSelected : L.Native.Ios.Editor.thumbnail).accessibilityIdentifier("thumb-\(url)")
                                }
                            }
                        }
                    }
                }
                Section(EditorUiCopy.shared.SHORT_DESCRIPTION) {
                    TextField(EditorUiCopy.shared.BRIEF_SUMMARY_OF_CONTENT, text: Binding(get: { editor.state.description_ }, set: { model.setDescription(text: $0) }), axis: .vertical).accessibilityIdentifier("description")
                }
                if !state.isEdit {
                    Section(EditorUiCopy.shared.BENEFICIARIES) {
                        row(state.owner ?? "", weight: state.authorWeight, removable: false)
                        let locked = EditorMeta.shared.hasThreeSpeakEmbed(text: state.body)
                        ForEach(state.beneficiaries, id: \.account) { b in
                            row(b.account, weight: b.weight, removable: !(locked && b.account == EditorMeta.shared.THREESPEAK_ACCOUNT))
                        }
                        // stacked at accessibility sizes: the two fields and the button do not fit one line
                        AdaptiveStack(spacing: 8) {
                            TextField("%", text: $percent).keyboardType(.numberPad).frame(minWidth: 56).fixedSize(horizontal: true, vertical: false).leftToRightField().accessibilityIdentifier("benef-percent")
                            TextField(EditorUiCopy.shared.USERNAME, text: $account).textInputAutocapitalization(.never).autocorrectionDisabled().leftToRightField().accessibilityIdentifier("benef-account")
                            Button(EditorUiCopy.shared.ADD_ACCOUNT) {
                                Task {
                                    benefError = try? await model.addBeneficiary(account: account, percent: Int32(percent) ?? 0)
                                    if benefError == nil { account = ""; percent = "" }
                                }
                            }
                            .buttonStyle(.borderless).accessibilityIdentifier("benef-add")
                        }
                        if let benefError { Text(benefError).font(.footnote).foregroundStyle(theme.dangerText).announces(benefError, debounce: true).accessibilityIdentifier("benef-error") }
                        if state.owner != EditorMeta.shared.ECENCY_SUPPORT_ACCOUNT {
                            Button { model.toggleSupport() } label: {
                                Text(EditorUiCopy.shared.supportEcency(percent: model.supportChipPercent)).font(.footnote).foregroundStyle(state.supportsEcency ? .white : theme.accentText)
                                    .padding(.horizontal, 12).padding(.vertical, 6).background(state.supportsEcency ? theme.accent : theme.lightBackground, in: Capsule())
                            }
                            .buttonStyle(.plain).accessibilityAddTraits(state.supportsEcency ? .isSelected : []).accessibilityIdentifier("support-chip")
                        }
                    }
                }
            }
            .navigationTitle(EditorUiCopy.shared.POST_OPTIONS).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(EditorUiCopy.shared.DONE_CAPS) { dismiss() }.accessibilityIdentifier("options-done") } }
            .onAppear {
                later = state.scheduledFor != nil
                if let iso = state.scheduledFor, let d = date(iso: iso) { when = d }
            }
            .alert(EditorUiCopy.shared.SAVE_AS_TEMPLATE, isPresented: $templateShown) {
                TextField(EditorUiCopy.shared.TEMPLATE_NAME, text: $templateName)
                Button(UiCopy.shared.CANCEL, role: .cancel) {}
                Button(UiCopy.shared.SAVE_CAPS) { if !templateName.trimmingCharacters(in: .whitespaces).isEmpty { model.saveAsTemplate(name: templateName) } }
            }
        }
    }

    private func row(_ account: String, weight: Int32, removable: Bool) -> some View {
        HStack {
            Text(Fmt.percent(weight / 100)).frame(minWidth: 56, alignment: .leading).fixedSize()
            Text("@\(account)")
            Spacer()
            if removable {
                Button { model.removeBeneficiary(account: account) } label: { Image(appIcon: .clear) }
                    .buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.REMOVE).accessibilityIdentifier("benef-remove-\(account)")
            }
        }
        .accessibilityIdentifier("benef-\(account)")
    }
}

/// RN selectCommunityModal.
private struct CommunitySheet: View {
    @StateObject private var picker: Observed<CommunityPickerState>
    let onPick: (EditorCommunity?) -> Void
    @Environment(\.dismiss) private var dismiss

    init(env: AppEnvironment, onPick: @escaping (EditorCommunity?) -> Void) {
        _picker = StateObject(wrappedValue: Observed(env.communityPickerModel(), ownsModel: true))
        self.onPick = onPick
    }

    private var model: CommunityPickerModel { picker.model as! CommunityPickerModel }

    private func row(_ c: EditorCommunity) -> some View {
        Button {
            Task { onPick((try? await model.resolve(choice: c)) ?? c) }
        } label: {
            HStack {
                AvatarView(url: "https://images.ecency.com/u/\(c.name)/avatar/small", size: 32)
                Text(c.title)
            }
        }
        .themedCardRow()
        .accessibilityIdentifier("community-\(c.name)")
    }

    var body: some View {
        let state = picker.state
        NavigationStack {
            List {
                if let results = state.results {
                    ForEach(results, id: \.name) { row($0) }
                } else {
                    Section(EditorUiCopy.shared.MY_BLOG_CAPS) { Button(EditorUiCopy.shared.MY_BLOG) { onPick(nil) }.themedCardRow().accessibilityIdentifier("community-my-blog") }
                    if !state.subscribed.isEmpty { Section(EditorUiCopy.shared.MY_COMMUNITIES) { ForEach(state.subscribed, id: \.name) { row($0) } } }
                    if !state.top.isEmpty { Section(EditorUiCopy.shared.TOP_COMMUNITIES) { ForEach(state.top, id: \.name) { row($0) } } }
                }
            }
            .themedList()
            .searchable(text: Binding(get: { picker.state.query }, set: { model.setQuery(text: $0) }), prompt: L.Native.Ios.Editor.communitySearch)
            .navigationTitle(L.Editor.selectCommunity).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { dismiss() } } }
            .onAppear { model.start() }
        }
    }
}

/// RN uploadsGalleryModal: where an image comes from, and the account's uploaded images. Without the source
/// handlers (the quick composers, `QuickUploadsSheet`) it is the gallery alone: no source tiles and no "+".
struct UploadsSheet: View {
    @ObservedObject var uploads: Observed<UploadsState>
    var onGallery: (() -> Void)?
    var onFiles: (() -> Void)?
    var onCamera: (() -> Void)?
    var onPaste: (() -> Void)?
    let onInsert: ([String]) -> Void
    var onAdd: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    private var model: UploadsModel { uploads.model as! UploadsModel }

    var body: some View {
        let state = uploads.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    // hasImages reads no content, so iOS shows no paste notice until the user taps
                    if onGallery != nil { MediaSourceTiles(
                        options: MediaSourceMenus.shared.editorUploads(camera: CameraPicker.isAvailable, paste: UIPasteboard.general.hasImages),
                        identifier: { source in
                            switch source {
                            case .files: return "open-files"
                            case .camera: return "open-camera"
                            case .paste: return "paste-image"
                            default: return "open-gallery"
                            }
                        }
                    ) { source in
                        switch source {
                        case .files: onFiles?()
                        case .camera: onCamera?()
                        case .paste: onPaste?()
                        default: onGallery?()
                        }
                    } }
                    HStack {
                        Text(EditorUiCopy.shared.UPLOADED_IMAGES).bold().accessibilityAddTraits(.isHeader)
                        Spacer()
                        // RN's "+" pill: add images to the gallery without inserting them
                        if let onAdd {
                            Button(action: onAdd) { Image(appIcon: .add).scaledFont(18, weight: .semibold).tapTarget() }
                                .accessibilityLabel(UploadsCopy.shared.ADD_TO_GALLERY)
                                .accessibilityIdentifier("add-to-gallery")
                        }
                        if state.uploading > 0 { ProgressView() }
                        if !state.selected.isEmpty { Button(EditorUiCopy.shared.deleteCount(count: Int32(state.selected.count)), role: .destructive) { model.deleteSelected() }.accessibilityIdentifier("delete-images") }
                    }
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3), spacing: 6) {
                        ForEach(state.images, id: \.url) { image in
                            let selected = state.selected.contains(image.id)
                            LoadedImageView(src: image.url, maxWidth: 140, contentMode: .scaleAspectFill) { _ in }
                                .frame(height: 104).frame(maxWidth: .infinity).background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? theme.danger : .clear, lineWidth: 3))
                                .contentShape(Rectangle())
                                .onTapGesture { if state.selected.isEmpty { onInsert([image.url]) } else { model.toggleSelected(id: image.id) } }
                                .onLongPressGesture { if !image.id.isEmpty { model.toggleSelected(id: image.id) } }
                                .accessibilityElement().accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton).accessibilityLabel(L.Native.Ios.Editor.uploadedImage)
                                // the long press that selects, for VoiceOver
                                .accessibilityAction(named: selected ? L.Native.Ios.A11yForms.deselect : L.Native.Ios.A11yForms.select) { if !image.id.isEmpty { model.toggleSelected(id: image.id) } }
                                .accessibilityIdentifier("gallery-image-\(image.id)")
                        }
                    }
                    if !state.endReached, state.loaded { Button(UiCopy.shared.LOAD_MORE) { model.loadMore() } }
                    if !state.loaded { ProgressView().frame(maxWidth: .infinity) }
                }
                .padding(16)
            }
            .navigationTitle(UiCopy.shared.IMAGE).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { model.clearSelection(); dismiss() } } }
            .onAppear { model.start() }
        }
        .accessibilityIdentifier("uploads-sheet")
    }
}

// MARK: Drafts

struct DraftsScreen: View {
    @StateObject private var drafts: Observed<DraftsState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @State private var confirm: DraftConfirm?
    @State private var unsynced = false

    private struct DraftConfirm: Identifiable {
        let id = UUID()
        let question: String
        /// Move to drafts (a default button) rather than delete (a destructive one).
        let moves: Bool
        let run: () -> Void
    }

    init(tab: Int32, env: AppEnvironment) {
        _drafts = StateObject(wrappedValue: Observed(env.draftsModel(tab: tab), ownsModel: true))
    }

    private var model: DraftsModel { drafts.model as! DraftsModel }

    var body: some View {
        let state = drafts.state
        VStack(spacing: 0) {
            Picker("", selection: Binding(get: { Int(drafts.state.tab) }, set: { model.setTab(tab: Int32($0)) })) {
                Text(EditorUiCopy.shared.DRAFTS).tag(0)
                Text(EditorUiCopy.shared.SCHEDULES).tag(1)
                Text(EditorUiCopy.shared.TEMPLATES).tag(2)
            }
            .pickerStyle(.segmented).padding(.horizontal, 16).padding(.vertical, 8).accessibilityIdentifier("drafts-tabs")
            List {
                switch state.tab {
                case 0:
                    if let local = state.localDraft {
                        card(id: "", title: local.title.isEmpty ? EditorUiCopy.shared.UNTITLED : local.title, body: local.body, tag: local.tags.first, date: "", selected: false) {
                            app.push(AppScreenEditor())
                        } trailing: {
                            Button { unsynced = true } label: { Image(appIcon: .warning).tapTarget() }.buttonStyle(.borderless).accessibilityLabel(EditorUiCopy.shared.UNSYNCED_DRAFT).accessibilityIdentifier("unsynced-info")
                        }
                    }
                    ForEach(state.drafts, id: \.id) { d in
                        card(id: d.id, title: d.title, body: d.body, tag: d.tags.first, date: d.modified.isEmpty ? d.created : d.modified, selected: state.selectedDrafts.contains(d.id)) {
                            app.push(AppScreenEditor.companion.of(arg: "draft:\(d.id)"))
                        } trailing: {
                            Button { model.cloneDraft(id: d.id) } label: { Image(appIcon: .clipboard).tapTarget() }.buttonStyle(.borderless).accessibilityLabel(EditorUiCopy.shared.CLONE).accessibilityIdentifier("clone-\(d.title)")
                            Button { confirm = DraftConfirm(question: UiCopy.shared.REMOVE_CONFIRM, moves: false) { model.deleteDraft(id: d.id) } } label: { Image(appIcon: .discard).tapTarget() }
                                .buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.DELETE).accessibilityIdentifier("delete-\(d.title)")
                        }
                    }
                    if state.loaded, !state.loadingDrafts, state.drafts.isEmpty, state.localDraft == nil { empty(UiCopy.shared.NOTHING_HERE) }
                case 1:
                    ForEach(state.schedules, id: \.id) { s in
                        card(id: s.id, title: s.title, body: s.body, tag: s.tags.first, date: s.schedule, selected: state.selectedSchedules.contains(s.id)) {
                            confirm = DraftConfirm(question: EditorUiCopy.shared.MOVE_TO_DRAFTS_CONFIRM, moves: true) { model.moveScheduleToDrafts(id: s.id) }
                        } trailing: {
                            Text(s.statusLabel).font(.caption).foregroundStyle(s.status == 3 ? theme.successText : (s.status == 1 || s.status == 2) ? theme.secondary : theme.dangerText)
                                .accessibilityIdentifier("schedule-status-\(s.title)")
                            Button { confirm = DraftConfirm(question: UiCopy.shared.REMOVE_CONFIRM, moves: false) { model.deleteSchedule(id: s.id) } } label: { Image(appIcon: .discard).tapTarget() }
                                .buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.DELETE).accessibilityIdentifier("delete-\(s.title)")
                        }
                    }
                    if state.loaded, !state.loadingSchedules, state.schedules.isEmpty { empty(UiCopy.shared.NOTHING_HERE) }
                default:
                    ForEach(state.templates, id: \.id) { d in
                        card(id: d.id, title: d.templateName.isEmpty ? EditorUiCopy.shared.UNTITLED_TEMPLATE : d.templateName, body: d.body, tag: d.tags.first, date: d.modified, selected: state.selectedDrafts.contains(d.id)) {
                            app.push(AppScreenEditor.companion.of(arg: "template:\(d.id)"))
                        } trailing: {
                            Button { confirm = DraftConfirm(question: EditorUiCopy.shared.DELETE_TEMPLATE_CONFIRM, moves: false) { model.deleteDraft(id: d.id) } } label: { Image(appIcon: .discard).tapTarget() }
                                .buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.DELETE).accessibilityIdentifier("delete-\(d.templateName)")
                        }
                    }
                    if state.loaded, !state.loadingDrafts, state.templates.isEmpty { empty(EditorUiCopy.shared.TEMPLATES_EMPTY) }
                }
                let end = state.tab == 1 ? state.schedulesEnd : state.draftsEnd
                if !end, state.loaded { Button(UiCopy.shared.LOAD_MORE) { model.loadMore() }.listStateRow().accessibilityIdentifier("drafts-more") }
                if let error = state.error { ErrorStateView(error: error, esie: nil) { model.refresh() } }
            }
            .listStyle(.plain)
            .themedList()
            .refreshable { model.refresh() }
            .overlay { if !state.loaded { ProgressView() } }
        }
        .background(theme.background)
        .navigationTitle(EditorUiCopy.shared.DRAFTS).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if state.hasSelection {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(UiCopy.shared.DELETE, role: .destructive) {
                        confirm = DraftConfirm(question: EditorUiCopy.shared.REMOVE_ALL_CONFIRM, moves: false) { model.deleteSelected() }
                    }
                    .accessibilityIdentifier("delete-selected")
                }
            }
        }
        .onAppear { model.start(); model.refreshLocalDraft() }
        .alert(item: $confirm) { c in
            Alert(title: Text(c.question), primaryButton: c.moves ? .default(Text(EditorUiCopy.shared.MOVE), action: c.run) : .destructive(Text(UiCopy.shared.DELETE), action: c.run), secondaryButton: .cancel())
        }
        .alert(EditorUiCopy.shared.UNSYNCED_DRAFT, isPresented: $unsynced) { Button(UiCopy.shared.OK) {} } message: { Text(EditorUiCopy.shared.UNSYNCED_BODY) }
        .accessibilityIdentifier("drafts-screen")
    }

    private func empty(_ text: String) -> some View {
        Text(text).foregroundStyle(theme.secondary).accessibilityIdentifier("drafts-empty").esieAbove(.empty).padding(.vertical, 24)
    }

    /// A draft's or schedule's ISO time in the app language and the device's time zone (README "Localization",
    /// formatting); text that does not parse shows as it came.
    static func shownDate(_ iso: String) -> String? {
        if iso.isEmpty { return nil }
        // the server writes UTC without a zone ("2026-10-26T12:00:00"): read as UTC, as Android does
        guard let millis = EditorMeta.shared.isoMillis(iso: iso) ?? EditorMeta.shared.isoMillis(iso: iso + "Z") else {
            return String(iso.prefix(16)).replacingOccurrences(of: "T", with: " ")
        }
        return LocaleFormat.shared.dateTime(epochMillis: millis.int64Value)
    }

    private func card<Trailing: View>(id: String, title: String, body: String, tag: String?, date: String, selected: Bool, open: @escaping () -> Void, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text([tag.map { "#\($0)" }, Self.shownDate(date)].compactMap { $0 }.joined(separator: "  "))
                    .font(.caption).foregroundStyle(theme.secondary)
                Text(title).bold().lineLimit(2).foregroundStyle(theme.text)
                Text(String(body.prefix(100))).font(.footnote).lineLimit(2).foregroundStyle(theme.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { if drafts.state.hasSelection, !id.isEmpty { model.toggleSelected(id: id) } else { open() } }
            .onLongPressGesture { if !id.isEmpty { model.toggleSelected(id: id) } }
            .accessibilityElement(children: .combine).accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            .accessibilityAction(named: selected ? L.Native.Ios.A11yForms.deselect : L.Native.Ios.A11yForms.select) { if !id.isEmpty { model.toggleSelected(id: id) } }
            .accessibilityIdentifier("draft-row-\(title)")
            if drafts.state.busy.contains(id), !id.isEmpty { ProgressView() } else { trailing() }
        }
        .listRowBackground(selected ? theme.lightBackground : theme.background)
    }
}

extension View {
    /// RN POSTING_AUTHORITY_PROMPT sheet ("Enable Faster Posting"), for the editor and, after a publish that
    /// HiveSigner refused for the missing authority, the wave composer and the quick reply.
    func postingAuthorityPrompt(isPresented: Bool, onAnswer: @escaping (Bool) -> Void, onDismiss: @escaping () -> Void) -> some View {
        alert(PostingAuthorityPrompt.shared.TITLE, isPresented: Binding(get: { isPresented }, set: { if !$0 { onDismiss() } })) {
            Button(PostingAuthorityPrompt.shared.GRANT) { onAnswer(true) }
            Button(PostingAuthorityPrompt.shared.SKIP, role: .cancel) { onAnswer(false) }
        } message: {
            Text(([PostingAuthorityPrompt.shared.DESCRIPTION] + PostingAuthorityPrompt.shared.BENEFITS.map { "\u{2022} " + $0 } + [PostingAuthorityPrompt.shared.SECURITY_NOTE]).joined(separator: "\n"))
        }
    }
}
