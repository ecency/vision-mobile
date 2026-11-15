import EcencyCore
import SwiftUI
import UIKit

/// The post menu's Select text (#3699): what the sheet shows and whom its Quote replies to.
struct SelectTextRequest {
    let post: Post
    let root: PostKey
}

extension AppContainer {
    /// An action on text selected in [post]'s body, shown in the discussion of [root] (#3699).
    func bodySelection(_ action: BodySelectionAction, text: String, post: Post, root: PostKey) {
        switch action {
        case .quote:
            guard session.state.isLoggedIn else {
                // from the Select text sheet a root alert would not show over it: the login sheet takes its place
                if sheet != nil { showAfterSheet(.login) } else { _ = requireSignIn(to: .reply) }
                return
            }
            // the composer is one per parent: the quote goes after its draft, and the sheet shows it
            _ = env.replyModel(root: root, parent: post).quote(selection: text)
            showAfterSheet(.reply(ReplyRequest(root: root, parent: post)))
        case .translate:
            showAfterSheet(.selectionTranslation(text))
        case .share:
            SystemShare.present(BodySelection.shared.shareText(selection: text, post: post))
        }
    }
}

/// The whole body as one selectable text, for selections across paragraphs; the same edit menu as the body's.
struct SelectTextSheet: View {
    let request: SelectTextRequest
    @StateObject private var model: Observed<SelectTextState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    init(request: SelectTextRequest, env: AppEnvironment) {
        self.request = request
        _model = StateObject(wrappedValue: Observed(env.selectTextModel(post: request.post), ownsModel: true))
    }

    var body: some View {
        NavigationStack {
            Group {
                if let text = model.state.text {
                    SelectableBodyText(text: text, color: UIColor(theme.text), tint: UIColor(theme.accent)) { action, selected in
                        app.bodySelection(action, text: selected, post: request.post, root: request.root)
                    }
                    .accessibilityIdentifier("select-text-body")
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(theme.background)
            .navigationTitle(L.Native.Reading.selectText)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(UiCopy.shared.DONE) { dismiss() }.accessibilityIdentifier("select-text-done") }
            }
        }
        .onAppear { (model.model as! SelectTextModel).start() }
    }
}

/// A scrolling, selectable, read-only text in the body font that follows Dynamic Type.
private struct SelectableBodyText: UIViewRepresentable {
    let text: String
    let color: UIColor
    let tint: UIColor
    let perform: (BodySelectionAction, String) -> Void

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.backgroundColor = .clear
        view.font = UIFont.preferredFont(forTextStyle: .body)
        view.adjustsFontForContentSizeCategory = true
        view.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 24, right: 12)
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.perform = perform
        if view.text != text { view.text = text }
        view.textColor = color
        view.tintColor = tint
    }

    func makeCoordinator() -> BodyMenuDelegate { BodyMenuDelegate(perform: perform) }
}

/// The body's edit menu (Copy, Quote, Translate, Share) over a plain text view.
final class BodyMenuDelegate: NSObject, UITextViewDelegate {
    var perform: (BodySelectionAction, String) -> Void
    init(perform: @escaping (BodySelectionAction, String) -> Void) { self.perform = perform }

    func textView(_ textView: UITextView, editMenuForTextIn range: NSRange, suggestedActions: [UIMenuElement]) -> UIMenu? {
        guard let text = textView.text, NSMaxRange(range) <= (text as NSString).length else { return nil }
        return BodySelectionAction.menu(in: textView, range: range, text: (text as NSString).substring(with: range),
                                        suggested: suggestedActions, perform: perform)
    }
}

/// A code block's text, selectable with the body's menu: monospaced and never wrapped (the block scrolls sideways).
struct SelectableCodeText: UIViewRepresentable {
    let text: String
    let color: UIColor
    let tint: UIColor
    let perform: (BodySelectionAction, String) -> Void
    /// Read so a Dynamic Type change updates the font.
    @Environment(\.dynamicTypeSize) private var dynamicType

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.perform = perform
        let font = UIFont.monospacedSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .subheadline).pointSize, weight: .regular)
        let attributed = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color])
        if view.attributedText != attributed { view.attributedText = attributed }
        view.tintColor = tint
    }

    /// The text's own size: as wide as its longest line, so nothing wraps.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let bounds = uiView.attributedText.boundingRect(with: CGSize(width: CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude),
                                                         options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        return CGSize(width: ceil(bounds.width) + 1, height: ceil(bounds.height))
    }

    func makeCoordinator() -> BodyMenuDelegate { BodyMenuDelegate(perform: perform) }
}
