import EcencyCore
import SwiftUI

/// #3724: the wallet's governance card (expired or expiring votes), its confirm sheet and its dismiss choice.
struct GovernanceCardView: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var observed: Observed<GovernanceCardState>
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.governanceCard, ownsModel: false))
    }

    private var model: GovernanceCardModel { observed.model as! GovernanceCardModel }

    var body: some View {
        let state = observed.state
        Group {
            if state.visible {
                VStack(alignment: .leading, spacing: 6) {
                    Text(state.title).scaledFont(17, weight: .semibold).foregroundStyle(theme.secondary)
                        .accessibilityAddTraits(.isHeader).accessibilityIdentifier("governance-title")
                    Text(state.body).scaledFont(14).foregroundStyle(theme.secondary).accessibilityIdentifier("governance-body")
                    if !state.done {
                        if state.action != nil {
                            Button { model.askConfirm() } label: {
                                Text(state.actionLabel).scaledFont(15, weight: .bold).frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.prominentFill)
                            .disabled(state.sending)
                            .padding(.top, 8)
                            .accessibilityIdentifier("governance-set-proxy")
                        }
                        AdaptiveStack(spacing: 16) {
                            Button(GovernanceCopy.shared.VOTE_YOURSELF) {
                                if let url = URL(string: GovernanceCardModel.companion.VOTE_URL) { app.sheetURL(url) }
                            }
                            .buttonStyle(.borderless)
                            .accessibilityIdentifier("governance-vote-yourself")
                            Button(GovernanceCopy.shared.DISMISS) { model.askDismiss() }.buttonStyle(.borderless).accessibilityIdentifier("governance-dismiss")
                        }
                        .padding(.top, 4)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(theme.lightBackground)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("governance-card")
            }
        }
        .themedSheet(isPresented: Binding(get: { observed.state.confirming }, set: { if !$0 && !observed.state.sending { model.cancelConfirm() } })) {
            GovernanceConfirmSheet(state: observed.state, onCancel: { model.cancelConfirm() }, onConfirm: { model.confirm() })
        }
        .confirmationDialog(GovernanceCopy.shared.DISMISS_TITLE, isPresented: Binding(get: { observed.state.askingDismiss }, set: { if !$0 && observed.state.askingDismiss { model.dismiss(choice: nil) } }),
                            titleVisibility: .visible) {
            Button(GovernanceCopy.shared.DONT_SHOW_AGAIN) { model.dismiss(choice: .forever) }.accessibilityIdentifier("governance-forever")
            Button(GovernanceCopy.shared.LATER) { model.dismiss(choice: .later) }.accessibilityIdentifier("governance-later")
            Button(GovernanceCopy.shared.CANCEL, role: .cancel) { model.dismiss(choice: nil) }
        }
    }
}

/// What a proxy to @ecency means, before anything is signed.
private struct GovernanceConfirmSheet: View {
    let state: GovernanceCardState
    let onCancel: () -> Void
    let onConfirm: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(state.sheetTitle).scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                ForEach(Array(state.sheetPoints.enumerated()), id: \.offset) { index, point in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(verbatim: "•").accessibilityHidden(true)
                        Text(point)
                    }
                    .scaledFont(15).foregroundStyle(theme.text)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("governance-point-\(index)")
                }
                AdaptiveStack(spacing: 8) {
                    Button(GovernanceCopy.shared.CANCEL, action: onCancel)
                        .disabled(state.sending)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("governance-cancel")
                    Button(action: onConfirm) {
                        if state.sending { ProgressView().tint(.white).frame(maxWidth: .infinity) } else { Text(state.confirmLabel).scaledFont(15, weight: .bold).frame(maxWidth: .infinity) }
                    }
                    .buttonStyle(.prominentFill)
                    .disabled(state.sending)
                    .accessibilityIdentifier("governance-confirm")
                }
                .padding(.top, 8)
            }
            .padding(24)
        }
        .presentationDetents([.medium, .large])
        // never closed while it signs and sends
        .interactiveDismissDisabled(state.sending)
    }
}
