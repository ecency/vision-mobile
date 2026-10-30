import EcencyCore
import SwiftUI

/// The Pro card on Perks (issue #3555): membership or the store's price; a tap opens `ProSheet`.
struct ProCard: View {
    @StateObject private var observed: Observed<ProState>
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.proModel(), ownsModel: true))
    }

    private var model: ProModel { observed.model as! ProModel }

    var body: some View {
        let s = observed.state
        // never empty: an empty Group gets no onAppear, and the model starts there (it learns the account)
        VStack(spacing: 0) {
            if s.account == nil {
                Color.clear.frame(height: 0).accessibilityHidden(true)
            } else {
                Button { model.open() } label: {
                    HStack(spacing: 12) {
                        Image(appIcon: .verified, filled: s.member).scaledFont(24).foregroundStyle(theme.accentText).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ProCopy.shared.TITLE).scaledFont(16, weight: .bold).foregroundStyle(theme.accentText)
                            Text(s.cardText).scaledFont(13).foregroundStyle(theme.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier(s.member ? "pro-card-member" : "pro-card-text")
                        }
                        Spacer(minLength: 0)
                        Image(appIcon: .chevronRight).foregroundStyle(theme.icon).accessibilityHidden(true)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, minHeight: minTapTarget, alignment: .leading)
                    .background(theme.lightBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint(ProCopy.shared.OPEN)
                .accessibilityIdentifier("pro-card")
            }
        }
        .onAppear { model.start() }
        .sheet(isPresented: Binding(get: { observed.state.open }, set: { if !$0 { model.dismiss() } })) {
            ProSheet(observed: observed).environment(\.appTheme, theme)
        }
    }
}

/// The Pro sheet: web's benefits, the store purchase (or a member's extension) and its outcome.
struct ProSheet: View {
    @ObservedObject var observed: Observed<ProState>
    @Environment(\.appTheme) private var theme

    private var model: ProModel { observed.model as! ProModel }
    private var copy: ProCopy { ProCopy.shared }

    var body: some View {
        let s = observed.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if s.succeeded {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(s.successTitle).scaledFont(16, weight: .bold).foregroundStyle(theme.text)
                            Text(s.successBody).scaledFont(14).foregroundStyle(theme.secondary)
                        }
                        .accessibilityElement(children: .combine)
                        .announces(s.successBody)
                        .accessibilityIdentifier("pro-success")
                    } else if s.activating {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(copy.ACTIVATING).scaledFont(14).foregroundStyle(theme.text)
                        }
                        .accessibilityElement(children: .combine)
                        .announces(copy.ACTIVATING)
                        .accessibilityIdentifier("pro-activating")
                    } else {
                        if s.member {
                            Text(copy.MEMBER).scaledFont(15, weight: .semibold).foregroundStyle(theme.text).accessibilityIdentifier("pro-member")
                        }
                        Text(copy.SUBTITLE).scaledFont(14).foregroundStyle(theme.secondary)
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(Array(copy.BENEFITS.enumerated()), id: \.offset) { i, benefit in
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Image(appIcon: .checkCircle).scaledFont(14).foregroundStyle(theme.accentText).accessibilityHidden(true)
                                    Text(benefit).scaledFont(14).foregroundStyle(theme.text).fixedSize(horizontal: false, vertical: true)
                                }
                                .accessibilityElement(children: .combine)
                                .accessibilityIdentifier("pro-benefit-\(i)")
                            }
                        }
                        if s.member { Text(copy.EXTEND_NOTE).scaledFont(13).foregroundStyle(theme.secondary) }
                    }
                    if let notice = s.notice {
                        Text(notice).scaledFont(13).foregroundStyle(theme.text).announces(notice).accessibilityIdentifier("pro-notice")
                    }
                    if s.showBuy {
                        Button { model.buy() } label: {
                            Group {
                                if s.buying { ProgressView().tint(.white) } else { Text(s.buyLabel ?? "").bold() }
                            }
                            .frame(maxWidth: .infinity, minHeight: 36)
                        }
                        .buttonStyle(.prominentFill)
                        .disabled(!s.canBuy)
                        .padding(.top, 8)
                        .accessibilityIdentifier("pro-buy")
                    } else if s.loadingPrice {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 8)
                    } else if s.unavailable && !s.purchased {
                        Text(copy.UNAVAILABLE).scaledFont(14).foregroundStyle(theme.secondary).accessibilityIdentifier("pro-unavailable")
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(theme.background)
            .navigationTitle(copy.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(copy.CLOSE) { model.dismiss() }.accessibilityIdentifier("pro-close")
                }
            }
        }
        .accessibilityIdentifier("pro-sheet")
        .alert(s.alert?.title ?? "", isPresented: Binding(get: { observed.state.alert != nil }, set: { if !$0 { model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.dismissAlert() }.accessibilityIdentifier("pro-alert-ok")
        } message: {
            Text(s.alert?.body ?? "")
        }
    }
}
