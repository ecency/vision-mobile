import EcencyCore
import SwiftUI

/// RN composeTranslateModal: translate the draft and add the translation to its end.
struct ComposeTranslateSheet: View {
    @StateObject private var translate: Observed<ComposeTranslateState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    init(editor: EditorModel, env: AppEnvironment, language: String) {
        // a model of its own each time the sheet opens (RN mounts it on show): it reads the body of that moment
        _translate = StateObject(wrappedValue: Observed(env.composeTranslateModel(editor: editor, appLanguage: language), ownsModel: true))
    }

    private var model: ComposeTranslateModel { translate.model as! ComposeTranslateModel }

    private func picker(_ title: String, id: String, current: String, options: [String], pick: @escaping (String) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(theme.secondary)
            Menu {
                ForEach(options, id: \.self) { code in
                    Button(Languages.shared.name(code: code)) { pick(code) }
                }
            } label: {
                HStack(spacing: 4) { Text(Languages.shared.name(code: current)); Image(appIcon: .dropdown).imageScale(.small).accessibilityHidden(true) }.foregroundStyle(theme.text)
                    .padding(.horizontal, 12).padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading)
                    .background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .accessibilityLabel(title)
            .accessibilityValue(Languages.shared.name(code: current))
            .accessibilityIdentifier(id)
        }
    }

    var body: some View {
        let state = translate.state
        let texts = ComposeTranslateModel.companion
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if state.loading {
                        ProgressView().frame(maxWidth: .infinity).padding(.vertical, 24)
                    } else if state.tooShort {
                        Text(texts.TOO_SHORT).foregroundStyle(theme.secondary).accessibilityIdentifier("translate-too-short")
                    } else {
                        Text(texts.REVIEW_NOTE).font(.footnote).foregroundStyle(theme.secondary)
                        AdaptiveStack(verticalAlignment: .bottom, spacing: 8) {
                            picker(EditorUiCopy.shared.SOURCE_LANGUAGE, id: "translate-source", current: state.source, options: state.sources) { model.setSource(code: $0) }
                            Image(appIcon: .arrowForward).foregroundStyle(theme.secondary).padding(.bottom, 12).accessibilityHidden(true)
                            picker(EditorUiCopy.shared.TARGET_LANGUAGE, id: "translate-target", current: state.target, options: state.targets) { model.setTarget(code: $0) }
                        }
                        if let detected = state.detectedText {
                            Text(detected).font(.caption).foregroundStyle(theme.secondary).accessibilityIdentifier("translate-detected")
                        }
                        if state.hasTitle {
                            Toggle(texts.ADD_TITLE_MARKER, isOn: Binding(get: { translate.state.addTitleMarker }, set: { model.setAddTitleMarker(on: $0) }))
                                .accessibilityIdentifier("translate-title-marker")
                        }
                        if state.translating {
                            HStack(spacing: 8) {
                                ProgressView()
                                if let progress = state.progressText { Text(progress).font(.footnote).foregroundStyle(theme.secondary) }
                            }
                            .accessibilityIdentifier("translate-progress")
                        }
                        if state.failed {
                            Text(texts.ERROR).font(.footnote).foregroundStyle(theme.dangerText).announces(texts.ERROR).accessibilityIdentifier("translate-error")
                        }
                        if !state.translated.isEmpty, !state.translating {
                            ScrollView {
                                Text(state.translated).font(.callout).foregroundStyle(theme.text)
                                    .frame(maxWidth: .infinity, alignment: state.rtl ? .trailing : .leading)
                                    .multilineTextAlignment(state.rtl ? .trailing : .leading)
                                    .padding(12)
                                    .accessibilityIdentifier("translate-preview")
                            }
                            .frame(maxHeight: 220).background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                        AdaptiveStack(spacing: 12) {
                            Button { model.translate() } label: { Text(UiCopy.shared.TRANSLATE).frame(maxWidth: .infinity) }
                                .buttonStyle(.prominentFill).disabled(!state.canTranslate).accessibilityIdentifier("translate-run")
                            Button { if model.apply() { dismiss() } } label: { Text(EditorUiCopy.shared.APPLY).frame(maxWidth: .infinity) }
                                .buttonStyle(.prominentFill).disabled(!state.canApply).accessibilityIdentifier("translate-apply")
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle(texts.TITLE).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { dismiss() }.accessibilityIdentifier("translate-close") } }
        }
        .onAppear { model.start() }
        .accessibilityIdentifier("translate-sheet")
    }
}

/// The line and the way out for a paid request without an answer ("Check again" is the main button).
struct UnresolvedNotice: View {
    let notice: String?
    let unresolved: Bool
    let running: Bool
    let id: String
    /// What the confirmation says: by default that the points may have been spent.
    var confirmText: String = PaidCopy.shared.GIVE_UP_CONFIRM
    let giveUp: () -> Void
    @State private var confirm = false
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let notice {
                Text(notice).font(.footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("\(id)-notice")
            }
            if unresolved, !running {
                Button(PaidCopy.shared.GIVE_UP, role: .destructive) { confirm = true }.font(.footnote).accessibilityIdentifier("\(id)-give-up")
            }
        }
        .alert(PaidCopy.shared.GIVE_UP, isPresented: $confirm) {
            Button(PaidCopy.shared.GIVE_UP, role: .destructive) { giveUp() }
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
        } message: { Text(confirmText) }
    }
}

/// RN aiAssistModal. The button charges (no confirmation step, as RN) and says what it costs. The editor's AI hub: with
/// `onImage` and `onDictate` it also lists the image generator and dictation, each opening its own sheet unchanged.
struct AiAssistSheet: View {
    @StateObject private var assist: Observed<AiAssistState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    private let onImage: (() -> Void)?
    private let onDictate: (() -> Void)?

    init(editor: AiTextTarget, env: AppEnvironment, onImage: (() -> Void)? = nil, onDictate: (() -> Void)? = nil) {
        _assist = StateObject(wrappedValue: Observed(env.aiAssistModel(editor: editor), ownsModel: true))
        self.onImage = onImage
        self.onDictate = onDictate
    }

    /// An AI hub row that opens another AI tool: the chevron says it leaves the sheet, unlike the actions above it.
    private func toolCard(_ title: String, _ description: String, id: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.bold()).foregroundStyle(enabled ? theme.text : theme.secondary)
                    Text(description).font(.caption).foregroundStyle(theme.secondary)
                }
                Spacer(minLength: 8)
                Image(appIcon: .chevronRight).foregroundStyle(theme.secondary).accessibilityHidden(true)
            }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .disabled(!enabled)
        .accessibilityIdentifier(id)
    }

    private var model: AiAssistModel { assist.model as! AiAssistModel }

    @ViewBuilder
    private func resultView(_ state: AiAssistState, _ result: AiAssistResult) -> some View {
        Text(EditorUiCopy.shared.RESULT).font(.footnote.bold()).foregroundStyle(theme.secondary)
        if result.isTitles {
            ForEach(Array(result.titles.enumerated()), id: \.offset) { index, title in
                let selected = Int32(index) == state.selectedTitle
                Button { model.selectTitle(index: Int32(index)) } label: {
                    Text(title).foregroundStyle(selected ? Color.white : theme.text).frame(maxWidth: .infinity, alignment: .leading).padding(12)
                        .background(selected ? theme.accent : theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("ai-title-\(index)")
            }
        } else if result.isTags {
            Text(result.tags.map { "#" + $0 }.joined(separator: "  ")).foregroundStyle(theme.accentText).frame(maxWidth: .infinity, alignment: .leading).padding(12)
                .background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityIdentifier("ai-tags")
        } else {
            ScrollView {
                Text(result.output).font(.callout).foregroundStyle(theme.text).frame(maxWidth: .infinity, alignment: .leading).padding(12).accessibilityIdentifier("ai-result")
            }
            .frame(maxHeight: 260).background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
        }
        // a Recap is copy only (RN replaced the whole body with it)
        if result.canApply {
            Button { if model.apply() { dismiss() } } label: { Text(EditorUiCopy.shared.APPLY_TO_POST).frame(maxWidth: .infinity) }
                .buttonStyle(.prominentFill).accessibilityIdentifier("ai-apply")
        }
        AdaptiveStack {
            Button(EditorUiCopy.shared.COPY) { if let value = model.copyValue { UIPasteboard.general.string = value } }.accessibilityIdentifier("ai-copy")
            FormRowGap()
            Button(UiCopy.shared.TRY_AGAIN) { model.tryAgain() }.accessibilityIdentifier("ai-try-again")
            FormRowGap()
            Button(EditorUiCopy.shared.TRY_ANOTHER) { model.tryAnother() }.accessibilityIdentifier("ai-try-another")
        }
    }

    @ViewBuilder
    private func formView(_ state: AiAssistState) -> some View {
        AdaptiveStack {
            Text(UiCopy.shared.balance(amount: state.balanceLabel)).accessibilityIdentifier("ai-balance")
            FormRowGap()
            if let cost = state.costText { Text(EditorUiCopy.shared.cost(cost: cost)).accessibilityIdentifier("ai-cost") }
        }
        .font(.subheadline).foregroundStyle(theme.text)
        Text(AiAssistModel.companion.HINT).font(.footnote).foregroundStyle(theme.secondary)
        if onImage != nil || onDictate != nil {
            // the other AI tools, a group of their own above the actions (RN's generate image card, outside the price
            // gate: each prices itself on its own sheet). Leaving would close a run that has no answer yet
            let busy = state.running || state.unresolved
            Text(EditorUiCopy.shared.AI_TOOLS).font(.footnote.bold()).foregroundStyle(theme.secondary)
            if let onImage { toolCard(EditorUiCopy.shared.AI_GENERATE_IMAGE, EditorUiCopy.shared.AI_GENERATE_IMAGE_DESC, id: "ai-tool-image", enabled: !busy, action: onImage) }
            if let onDictate { toolCard(EditorUiCopy.shared.DICTATE, EditorUiCopy.shared.DICTATE_DESC, id: "ai-tool-dictate", enabled: !busy, action: onDictate) }
            Spacer().frame(height: 6)
        }
        Text(EditorUiCopy.shared.SELECT_AN_ACTION).font(.footnote.bold()).foregroundStyle(theme.secondary)
        if state.loadingPrices {
            ProgressView().frame(maxWidth: .infinity)
        } else {
            ForEach(state.actions, id: \.self) { action in
                let selected = state.action == action
                Button { model.selectAction(action: action) } label: {
                    AdaptiveStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(AiAssistModel.companion.actionName(action: action)).font(.subheadline.bold()).foregroundStyle(selected ? Color.white : theme.text)
                            Text(AiAssistModel.companion.actionDescription(action: action)).font(.caption).foregroundStyle(selected ? Color.white : theme.secondary)
                        }
                        FormRowGap()
                        Text(AiAssistModel.companion.priceLine(price: state.prices.first { $0.action == action })).font(.caption).foregroundStyle(selected ? Color.white : theme.secondary)
                    }
                    .padding(12).background(selected ? theme.accent : theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .disabled(state.unresolved || state.running).accessibilityAddTraits(selected ? .isSelected : []).accessibilityIdentifier("ai-action-\(action)")
            }
        }
        if state.action != nil {
            Text(EditorUiCopy.shared.YOUR_TEXT).font(.footnote.bold()).foregroundStyle(theme.secondary)
            TextEditor(text: Binding(get: { assist.state.text }, set: { model.setText(text: $0) }))
                .frame(minHeight: 100, maxHeight: 220).padding(4).overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border))
                .disabled(state.unresolved || state.running).accessibilityIdentifier("ai-text")
            Text(state.countText).font(.caption).foregroundStyle(Int(state.text.utf16.count) < Int(state.minInput) ? theme.dangerText : theme.secondary).accessibilityIdentifier("ai-count")
        }
        if let insufficient = state.insufficientText {
            Text(insufficient).font(.footnote).foregroundStyle(theme.dangerText).announces(insufficient, debounce: true).accessibilityIdentifier("ai-insufficient")
        }
        UnresolvedNotice(notice: state.notice, unresolved: state.unresolved, running: state.running, id: "ai") { model.giveUp() }
        Button { model.submit() } label: {
            Group { if state.running { ProgressView().tint(.white) } else { Text(state.submitLabel) } }.frame(maxWidth: .infinity)
        }
        .buttonStyle(.prominentFill).disabled(!state.canSubmit).accessibilityIdentifier("ai-submit")
    }

    var body: some View {
        let state = assist.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if let result = state.result { resultView(state, result) } else { formView(state) }
                }
                .padding(16)
            }
            .navigationTitle(AiAssistModel.companion.TITLE).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { dismiss() }.accessibilityIdentifier("ai-close") } }
        }
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { assist.state.alert != nil }, set: { if !$0 { model.clearAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.clearAlert() }
        } message: { Text(state.alert ?? "") }
        .onAppear { model.start() }
        .accessibilityIdentifier("ai-assist-sheet")
    }
}

/// RN aiImageGeneratorScreen, as a sheet over the editor that opened it. The button charges and says what it costs.
struct AiImageSheet: View {
    @StateObject private var image: Observed<AiImageState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    /// Opened as a screen of its own (the AiImageGeneratorScreen route): no editor, so nothing to insert into.
    private let standalone: Bool

    init(editor: EditorModel?, env: AppEnvironment) {
        standalone = editor == nil
        _image = StateObject(wrappedValue: Observed(editor.map { env.aiImageModel(editor: $0) } ?? env.aiImageScreenModel(), ownsModel: true))
    }

    private var model: AiImageModel { image.model as! AiImageModel }

    private func pill(_ title: String, active: Bool, enabled: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline).foregroundStyle(active ? Color.white : theme.text).padding(.horizontal, 14).padding(.vertical, 8)
                .background(active ? theme.accent : theme.lightBackground).clipShape(Capsule())
        }
        .disabled(!enabled).accessibilityAddTraits(active ? .isSelected : []).accessibilityIdentifier(id)
    }

    @ViewBuilder
    private func resultView(_ state: AiImageState, _ address: String) -> some View {
        AsyncImage(url: URL(string: address)) { phase in
            if let picture = phase.image { picture.resizable().scaledToFit() } else { theme.lightBackground }
        }
        .aspectRatio(CGFloat(state.ratioNumber), contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 8)).keepsColorsInverted().accessibilityIdentifier("ai-image-result")
        AdaptiveStack(spacing: 12) {
            if state.canInsert {
                Button { if model.insert() { dismiss() } } label: { Text(EditorUiCopy.shared.INSERT_INTO_POST).frame(maxWidth: .infinity) }
                    .buttonStyle(.prominentFill).accessibilityIdentifier("ai-image-insert")
            }
            if let url = URL(string: address) {
                ShareLink(item: url) { Text(UiCopy.shared.SHARE).frame(maxWidth: .infinity) }.buttonStyle(.prominentFill).accessibilityIdentifier("ai-image-share")
            }
        }
        Button(EditorUiCopy.shared.GENERATE_AGAIN) { model.generateAgain() }.frame(maxWidth: .infinity).accessibilityIdentifier("ai-image-again")
    }

    @ViewBuilder
    private func formView(_ state: AiImageState) -> some View {
        let editable = !state.running && !state.unresolved
        Text(AiImageModel.companion.PROMPT_LABEL).font(.footnote.bold()).foregroundStyle(theme.secondary)
        TextEditor(text: Binding(get: { image.state.prompt }, set: { model.setPrompt(text: $0) }))
            .frame(minHeight: 100, maxHeight: 200).padding(4).overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border))
            .disabled(!editable).accessibilityIdentifier("ai-image-prompt")
        Text(state.remainingText).font(.caption).foregroundStyle(theme.secondary)
        if let suggestion = state.suggestionText {
            Button { model.useSuggestion() } label: {
                Text(suggestion).font(.footnote).lineLimit(2).padding(.horizontal, 12).padding(.vertical, 6).overlay(Capsule().stroke(theme.accent))
            }
            .accessibilityIdentifier("ai-image-suggestion")
        }
        Text(EditorUiCopy.shared.SELECT_ASPECT_RATIO).font(.footnote.bold()).foregroundStyle(theme.secondary)
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(AiImageModel.companion.RATIOS, id: \.self) { ratio in
                    pill(ratio, active: ratio == state.aspectRatio, enabled: editable, id: "ai-ratio-\(ratio)") { model.setAspectRatio(ratio: ratio) }
                }
            }
        }
        if !state.powerTiers.isEmpty {
            Text(EditorUiCopy.shared.QUALITY_BOOST).font(.footnote.bold()).foregroundStyle(theme.secondary)
            HStack(spacing: 8) {
                ForEach(state.powerTiers.map { $0.int32Value }, id: \.self) { power in
                    pill("\(power)x", active: power == state.power, enabled: editable, id: "ai-power-\(power)") { model.setPower(power: power) }
                }
            }
        }
        UnresolvedNotice(notice: state.notice, unresolved: state.unresolved, running: state.running, id: "ai-image") { model.giveUp() }
        if state.running {
            HStack(spacing: 12) {
                ProgressView()
                Text(AiImageModel.companion.GENERATING).font(.subheadline).foregroundStyle(theme.secondary)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 12).accessibilityIdentifier("ai-image-generating")
        } else {
            Button { model.generate() } label: { Text(state.generateLabel).frame(maxWidth: .infinity) }
                .buttonStyle(.prominentFill).disabled(!state.canGenerate).accessibilityIdentifier("ai-image-generate")
        }
    }

    private func content(_ state: AiImageState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                AdaptiveStack {
                    Text(UiCopy.shared.balance(amount: state.balanceLabel)).accessibilityIdentifier("ai-image-balance")
                    FormRowGap()
                    Text(EditorUiCopy.shared.cost(cost: state.costLabel)).accessibilityIdentifier("ai-image-cost")
                }
                .font(.subheadline).foregroundStyle(theme.text).padding(12).background(theme.lightBackground).clipShape(RoundedRectangle(cornerRadius: 8))
                if let address = state.resultUrl { resultView(state, address) } else { formView(state) }
            }
            .padding(16)
        }
        .navigationTitle(AiImageModel.companion.TITLE).navigationBarTitleDisplayMode(.inline)
    }

    var body: some View {
        let state = image.state
        Group {
            if standalone {
                content(state)
            } else {
                NavigationStack {
                    content(state).toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { dismiss() }.accessibilityIdentifier("ai-image-close") } }
                }
            }
        }
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { image.state.alert != nil }, set: { if !$0 { model.clearAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.clearAlert() }
        } message: { Text(state.alert ?? "") }
        .onAppear { model.start() }
        .accessibilityIdentifier("ai-image-sheet")
    }
}

/// RN dictationModal: record, stop, and the transcript goes into the post; the sheet stays open for more. Stop is
/// the tap that spends points, and says how many. A recording goes on in the background and ends when this sheet
/// goes away (closing it closes the model, which stops the microphone; README "Background media").
struct DictationSheet: View {
    @StateObject private var dictation: Observed<DictationState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    init(editor: AiTextTarget, env: AppEnvironment) {
        _dictation = StateObject(wrappedValue: Observed(env.dictationModel(editor: editor), ownsModel: true))
    }

    private var model: DictationModel { dictation.model as! DictationModel }
    /// The clock's 44 pt, following Dynamic Type.
    @ScaledMetric(relativeTo: .largeTitle) private var clockSize: CGFloat = 44

    var body: some View {
        let state = dictation.state
        let texts = DictationModel.companion
        let recording = state.phase == DictationPhase.recording
        NavigationStack {
            // scrolls when large text needs more than the sheet's height
            ScrollView {
            VStack(spacing: 8) {
                if state.unavailable { Text(texts.UNAVAILABLE).font(.footnote).foregroundStyle(theme.dangerText).announces(texts.UNAVAILABLE) }
                if state.denied { Text(texts.DENIED).font(.footnote).foregroundStyle(theme.dangerText).announces(texts.DENIED).accessibilityIdentifier("dictation-denied") }
                if state.recorderFailed { Text(texts.RECORDER_ERROR).font(.footnote).foregroundStyle(theme.dangerText).announces(texts.RECORDER_ERROR).accessibilityIdentifier("dictation-recorder-error") }
                if state.priceFailed {
                    Text(texts.PRICE_ERROR).font(.footnote).foregroundStyle(theme.dangerText).accessibilityIdentifier("dictation-price-error")
                    Button(UiCopy.shared.TRY_AGAIN) { model.reloadPrice() }
                }
                Text(state.clock).font(.system(size: clockSize, weight: .bold).monospacedDigit()).minimumScaleFactor(0.5).lineLimit(1).foregroundStyle(theme.text).accessibilityIdentifier("dictation-clock")
                if recording {
                    HStack(spacing: 6) {
                        Circle().fill(theme.danger).frame(width: 10, height: 10).accessibilityHidden(true)
                        Text(EditorUiCopy.shared.RECORDING).font(.footnote).foregroundStyle(theme.dangerText)
                    }
                    .accessibilityIdentifier("dictation-recording")
                }
                Text(state.costLine).font(.callout).foregroundStyle(theme.text).accessibilityIdentifier("dictation-cost")
                if let balance = state.balanceLine { Text(balance).font(.footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("dictation-balance") }
                if let free = state.freeLine { Text(free).font(.footnote).foregroundStyle(theme.secondary) }
                if let hint = state.hintLine { Text(hint).font(.footnote).foregroundStyle(theme.secondary).multilineTextAlignment(.center).accessibilityIdentifier("dictation-hint") }
                UnresolvedNotice(notice: state.notice, unresolved: state.retry != nil, running: state.busy, id: "dictation", confirmText: state.giveUpConfirm) { model.giveUp() }
                AdaptiveStack(spacing: 12) {
                    Button { model.main() } label: { Text(state.mainLabel).frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent).tint(recording ? theme.danger : theme.accent).disabled(!state.mainEnabled).accessibilityIdentifier("dictation-main")
                    Button { dismiss() } label: { Text(UiCopy.shared.DONE).frame(maxWidth: .infinity) }
                        .buttonStyle(.prominentFill).disabled(state.busy).accessibilityIdentifier("dictation-done")
                }
                .padding(.top, 8)
                if state.phase == DictationPhase.transcribing {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(EditorUiCopy.shared.TRANSCRIBING).font(.footnote).foregroundStyle(theme.secondary)
                    }
                    .accessibilityIdentifier("dictation-transcribing")
                }
            }
            .padding(16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle(texts.TITLE).navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(state.busy)
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { dictation.state.alert != nil }, set: { if !$0 { model.clearAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.clearAlert() }
        } message: { Text(state.alert ?? "") }
        .onAppear { model.start() }
        .onChange(of: state.askPermission) { ask in
            // the system's microphone prompt, on the first Record
            if ask { IosDictationRecorder.requestPermission { granted in model.permissionAnswered(granted: granted) } }
        }
        .accessibilityIdentifier("dictation-sheet")
    }
}

/// RN speakUploaderModal: "Record Video" or "Choose from Library", then the looping preview, the thumbnail (a
/// frame of the strip, or another image through the pencil), the progress and Close / Start Upload. The upload
/// model lives with the editor screen or the wave composer, so a failed upload can be continued after this sheet
/// was closed.
struct VideoUploadSheet: View {
    @ObservedObject var video: Observed<VideoUploadState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @Environment(\.scenePhase) private var scenePhase
    @State private var picker: Source?
    @State private var frames: [UIImage] = []
    @State private var customThumb: UIImage?
    @State private var notice: String?
    /// The Files importer's surface while it is open (the video or its thumbnail).
    @State private var files: MediaSurface?
    @State private var thumbChoice = false

    private enum Source: String, Identifiable {
        case library, camera, thumbnail
        var id: String { rawValue }
    }

    private func pickedThumb(_ images: [PickedImage]) {
        guard let image = images.first else { notice = UiCopy.shared.INVALID_IMAGE; return }
        model.setCustomThumb(image: image)
        customThumb = UIImage(data: EditorVideos.data(image.bytes))
    }

    private var model: VideoUploadModel { video.model as! VideoUploadModel }

    private func picked(_ url: URL?, _ name: String?) {
        picker = nil
        guard let url else { return }
        Task {
            frames = []
            customThumb = nil
            if let cut = await EditorVideos.adopt(url, name: name, into: model) { frames = cut }
        }
    }

    /// A video that was picked or recorded and never sent goes with the sheet (and its copy).
    private func close() {
        model.dismissed()
        dismiss()
    }

    /// RN `_effectiveThumb`: the image that will be the thumbnail.
    private func currentThumb(_ state: VideoUploadState) -> UIImage? {
        if state.customThumb { return customThumb }
        guard let index = state.effectiveThumb?.intValue, frames.indices.contains(index) else { return nil }
        return frames[index]
    }

    var body: some View {
        let state = video.state
        NavigationStack {
            // scrolls when large text needs more than the sheet's height
            ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !state.hasVideo {
                    Button(VideoUploadModel.companion.RECORD) {
                        if VideoCameraPicker.isAvailable { picker = .camera } else { notice = VideoUploadModel.companion.NO_CAMERA }
                    }
                    .accessibilityIdentifier("video-record")
                    Button(VideoUploadModel.companion.CHOOSE) { picker = .library }.accessibilityIdentifier("video-choose")
                    Button(MediaPickCopy.shared.FILES) { files = .video }.accessibilityIdentifier("video-files")
                    // a video that cannot be used (RN's notice: a short over 60 seconds) is said here
                    if let error = state.error { Text(error).font(.footnote).foregroundStyle(theme.dangerText).announces(error).accessibilityIdentifier("video-error") }
                } else {
                    if let ref = state.previewRef {
                        LoopingVideoView(url: URL(fileURLWithPath: ref), playing: state.previewPlays && scenePhase == .active)
                            .frame(maxWidth: .infinity).frame(height: 180).clipShape(RoundedRectangle(cornerRadius: 8))
                            .accessibilityIdentifier("video-preview")
                    }
                    Text(EditorUiCopy.shared.fileSize(name: state.fileName, size: LocaleFormat.shared.toFixed(value: Double(state.sizeBytes) / 1_048_576, decimals: 1))).font(.subheadline).foregroundStyle(theme.text).accessibilityIdentifier("video-name")
                    Text(VideoUploadModel.companion.SELECT_THUMB).font(.footnote.bold()).foregroundStyle(theme.secondary)
                    if state.generatingThumbs { ProgressView() }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            // RN's header: the thumbnail as it will be, with a pencil; a tap picks another image for it
                            Button { thumbChoice = true } label: {
                                ZStack(alignment: .topLeading) {
                                    if let shown = currentThumb(state) {
                                        Image(uiImage: shown).resizable().scaledToFill().frame(width: 96, height: 64)
                                    } else {
                                        theme.lightBackground.frame(width: 96, height: 64)
                                    }
                                    Image(appIcon: .edit).scaledFont(13, weight: .bold).foregroundStyle(.white)
                                        .padding(4).background(Color.black.opacity(0.6), in: Circle()).padding(4)
                                }
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                                .overlay(RoundedRectangle(cornerRadius: 6).stroke(state.customThumb ? theme.accent : Color.clear, lineWidth: 3))
                            }
                            .disabled(state.uploading)
                            .accessibilityLabel(L.Native.Ios.Editor.thumbnailPickAnother)
                            .accessibilityIdentifier("video-thumb-edit")
                            Rectangle().fill(theme.icon).frame(width: 1, height: 48).accessibilityHidden(true)
                            ForEach(Array(frames.enumerated()), id: \.offset) { index, frame in
                                let selected = state.effectiveThumb?.intValue == index
                                Button { model.selectThumb(index: Int32(index)) } label: {
                                    Image(uiImage: frame).resizable().scaledToFill().frame(width: 96, height: 64).clipShape(RoundedRectangle(cornerRadius: 6))
                                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(selected ? theme.accent : Color.clear, lineWidth: 3))
                                }
                                .disabled(state.uploading)
                                .accessibilityLabel(selected ? L.Native.Ios.Editor.thumbnailSelected : L.Native.Ios.Editor.thumbnail)
                                .accessibilityAddTraits(selected ? .isSelected : [])
                                .accessibilityIdentifier("video-thumb-\(index)")
                            }
                        }
                    }
                    ProgressView(value: min(max(state.progress / 100, 0), 1)).tint(theme.accentText).accessibilityIdentifier("video-progress")
                    if let error = state.error { Text(error).font(.footnote).foregroundStyle(theme.dangerText).announces(error).accessibilityIdentifier("video-error") }
                }
                AdaptiveStack {
                    Button(state.uploading ? UiCopy.shared.CANCEL : UiCopy.shared.CLOSE) { if state.uploading { model.cancel() } else { close() } }.accessibilityIdentifier("video-close")
                    FormRowGap()
                    if state.hasVideo {
                        Button(state.startLabel) { model.start() }.buttonStyle(.prominentFill).disabled(!state.canStart).accessibilityIdentifier("video-start")
                    }
                }
            }
            .padding(16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle(VideoUploadModel.companion.SELECT_SOURCE).navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(state.uploading)
        .sheet(item: $picker) { which in
            switch which {
            case .library: VideoPicker { url, name in picked(url, name) }.ignoresSafeArea()
            case .camera: VideoCameraPicker { url in picked(url, nil) }.ignoresSafeArea()
            case .thumbnail:
                // RN _handleOpenImagePicker: prepared like any upload (no metadata, 1920 px), then the signed image upload
                PhotoPicker(limit: 1, onResults: { count in if count == 0 { picker = nil } }, onPicked: { images in
                    picker = nil
                    pickedThumb(images)
                }).ignoresSafeArea()
            }
        }
        .mediaSourceChooser(isPresented: $thumbChoice, title: VideoUploadModel.companion.SELECT_THUMB, options: MediaSourceMenus.shared.attachment(),
                            identifier: { $0 == .files ? "video-thumb-files" : "video-thumb-library" }) { source in
            if source == .files { files = .videoThumbnail } else { picker = .thumbnail }
        }
        .mediaFileImporter(surface: $files, onImages: { _, images in if !images.isEmpty { pickedThumb(images) } }, onVideo: { url, name in picked(url, name) })
        .alert(EditorUiCopy.shared.NOTICE, isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
            Button(UiCopy.shared.OK, role: .cancel) { notice = nil }
        } message: { Text(notice ?? "") }
        // swiped away: the same as Close
        .onDisappear { if !video.state.uploading { model.dismissed() } }
        .onChange(of: state.done) { done in if done { dismiss() } }
        .accessibilityIdentifier("video-sheet")
    }
}
