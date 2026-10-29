import EcencyCore
import SwiftUI

/// RN newsletterDigestSheet, app-wide like RN's registered sheet: it shows while the shared model says so (the
/// offer after an account's first post) and only for the account it was opened for. The app lock's window covers
/// it like everything else.
struct DigestSheetHost: View {
    @StateObject private var digest: Observed<DigestState>
    @StateObject private var root: Observed<RootPromptState>
    private let env: AppEnvironment

    init(env: AppEnvironment) {
        self.env = env
        _digest = StateObject(wrappedValue: Observed(env.digest, ownsModel: false))
        _root = StateObject(wrappedValue: Observed(env.rootPrompts, ownsModel: false))
    }

    private var model: DigestModel { digest.model as! DigestModel }

    /// Never shown for another account, even for the moment before the model closes on the session change, and
    /// only when it is its turn among the root prompts (shared RootPromptRule).
    private var isOpen: Bool {
        let state = digest.state
        return root.state.prompt == .digest && state.open && state.owner != nil && state.owner == env.session.current.username
    }

    var body: some View {
        Color.clear.frame(width: 0, height: 0).allowsHitTesting(false)
            .sheet(isPresented: Binding(get: { isOpen }, set: { if !$0 { model.dismiss() } })) {
                DigestSheet(state: digest.state, model: model)
            }
    }
}

struct DigestSheet: View {
    let state: DigestState
    let model: DigestModel
    @Environment(\.appTheme) private var theme
    /// The field's own text (the watched state arrives a main-queue turn after `setEmail`).
    @State private var email = ""
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // scrolls when large text makes it taller than the sheet
        ScrollView {
        VStack(spacing: 12) {
            if let sentTo = state.checkInboxEmail {
                title(DigestCopy.shared.CHECK_INBOX_TITLE)
                description(DigestCopy.shared.checkInboxBody(email: sentTo)).accessibilityIdentifier("digest-check-inbox")
                primary(DigestCopy.shared.OK, enabled: true) { model.dismiss() }.accessibilityIdentifier("digest-ok")
            } else if state.loading {
                ProgressView().padding(.vertical, 24).accessibilityIdentifier("digest-loading")
            } else if state.unavailable {
                description(DigestCopy.shared.UNAVAILABLE).accessibilityIdentifier("digest-unavailable")
                Button(DigestCopy.shared.CANCEL) { model.dismiss() }.foregroundStyle(theme.secondary).accessibilityIdentifier("digest-cancel")
            } else {
                title(state.title).accessibilityIdentifier("digest-title")
                description(state.body)
                if let status = state.status {
                    Text(status).scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.accentText).announces(status)
                        .accessibilityIdentifier("digest-status")
                }
                AdaptiveStack(horizontalAlignment: .center, spacing: 8) {
                    cadence("weekly", DigestCopy.shared.WEEKLY)
                    cadence("monthly", DigestCopy.shared.MONTHLY)
                }
                if state.needsEmailInput {
                    TextField(DigestCopy.shared.EMAIL_PLACEHOLDER, text: $email)
                        .onChange(of: email) { model.setEmail(email: email) }
                        .keyboardType(.emailAddress).textContentType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(.horizontal, 14).padding(.vertical, 12)
                        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                        .disabled(state.isPending)
                        .accessibilityIdentifier("digest-email")
                }
                primary(state.primaryLabel, enabled: state.canSubmit) { model.subscribe() }.accessibilityIdentifier("digest-submit")
                if state.showsLeave {
                    Button(DigestCopy.shared.LEAVE) { model.leave() }.foregroundStyle(.red).disabled(state.isPending).accessibilityIdentifier("digest-leave")
                }
                Button(DigestCopy.shared.CANCEL) { model.dismiss() }.foregroundStyle(theme.secondary).accessibilityIdentifier("digest-cancel")
            }
        }
        .padding(.horizontal, 20).padding(.top, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .background(theme.background)
        .presentationDetents(typeSize > .large ? [.medium, .large] : [.medium])
        .alert(state.alert ?? "", isPresented: Binding(get: { state.alert != nil }, set: { if !$0 { model.clearAlert() } })) {
            Button(DigestCopy.shared.OK, role: .cancel) { model.clearAlert() }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("digest-sheet")
    }

    private func title(_ text: String) -> some View {
        Text(text).scaledFont(20, weight: .bold).foregroundStyle(theme.text).multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
    }

    private func description(_ text: String) -> some View {
        Text(text).scaledFont(15, relativeTo: .subheadline).foregroundStyle(theme.secondary).multilineTextAlignment(.center).padding(.bottom, 4)
    }

    private func cadence(_ value: String, _ label: String) -> some View {
        let selected = state.effectiveCadence == value
        return Button { model.setCadence(cadence: value) } label: {
            Text(label).scaledFont(14, relativeTo: .subheadline).foregroundStyle(selected ? Color.white : theme.text)
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(selected ? theme.accent : theme.lightBackground, in: Capsule())
                .frame(minHeight: minTapTarget).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(state.isPending)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("digest-cadence-\(value)")
    }

    private func primary(_ text: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).scaledFont(15, weight: .bold).foregroundStyle(.white).frame(maxWidth: .infinity).padding(.vertical, 12)
                .background(enabled ? theme.accent : theme.icon, in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
