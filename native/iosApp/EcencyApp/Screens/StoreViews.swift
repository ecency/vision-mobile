import EcencyCore
import SwiftUI

/// RN `$companyRed`: the deal ribbon.
private let companyRed = Color(red: 0xC1 / 255, green: 0, blue: 0)

/// RN ProductItemLine: the deal ribbon ("BEST DEAL!", "POPULAR!") on the left, the blue button with the product's title
/// and a "+" in a white circle, the store's price at the right.
struct ProductItemLine: View {
    let row: StoreRow
    let enabled: Bool
    let onBuy: (String) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        // the ribbon, button and price stack at accessibility sizes (side by side they ran off screen)
        AdaptiveStack(spacing: 0) {
            if let deal = row.deal {
                Text(deal).scaledFont(10, weight: .bold).foregroundStyle(.white)
                    .padding(.horizontal, 16).frame(minWidth: 90, minHeight: 40, alignment: .leading)
                    .background(companyRed)
                    .clipShape(UnevenRoundedRectangle(bottomTrailingRadius: 5, topTrailingRadius: 5))
                    .overlay(alignment: .trailing) { DealNotch().fill(theme.background).frame(width: 14, height: 36) }
            }
            FormRowGap(minLength: 8)
            Button { onBuy(row.productId) } label: {
                HStack(spacing: 16) {
                    Text(row.title).scaledFont(14, weight: .bold).foregroundStyle(.white).frame(minWidth: 110, maxWidth: 140, alignment: .leading)
                    Image(appIcon: .add).scaledFont(14, weight: .bold).foregroundStyle(theme.accentText)
                        .frame(width: 24, height: 24).background(.white).clipShape(Circle())
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 18).frame(minHeight: 50).background(theme.accent).clipShape(Capsule())
                .opacity(enabled ? 1 : 0.5)
            }
            .buttonStyle(.plain).disabled(!enabled).padding(.vertical, 12)
            .accessibilityIdentifier("buy-\(row.productId)")
            Text(row.price).scaledFont(14, weight: .bold).foregroundStyle(theme.accentText).frame(minWidth: 96)
                .accessibilityIdentifier("price-\(row.productId)")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("product-\(row.productId)")
    }
}

/// RN's triangle: the background color notched into the ribbon's right edge.
private struct DealNotch: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}

/// The alert and the link's confirmation of a purchase screen, and the free points row's navigation.
private struct StoreDialogs: ViewModifier {
    @ObservedObject var observed: Observed<StoreScreenState>
    let onSpinGame: () -> Void

    private var model: StoreScreenModel { observed.model as! StoreScreenModel }

    func body(content: Content) -> some View {
        let s = observed.state
        content
            .onChange(of: s.openSpinGame) { _, open in
                if open {
                    model.consumeOpenSpinGame()
                    onSpinGame()
                }
            }
            .alert(s.alert?.title ?? "", isPresented: Binding(get: { observed.state.alert != nil }, set: { if !$0 { model.dismissAlert() } }), presenting: s.alert) { _ in
                Button(UiCopy.shared.OK, role: .cancel) { model.dismissAlert() }.accessibilityIdentifier("store-alert-ok")
            } message: { a in
                Text(a.body)
            }
            .alert(s.confirm?.title ?? "", isPresented: Binding(get: { observed.state.confirm != nil }, set: { if !$0 { model.dismissConfirm() } }),
                   presenting: s.confirm) { _ in
                Button(PurchaseCopy.shared.CONFIRM) { model.confirm() }.accessibilityIdentifier("store-confirm")
                Button(PurchaseCopy.shared.CANCEL, role: .cancel) { model.dismissConfirm() }
            } message: { c in
                Text(c.body)
            }
    }
}

/// RN boostScreen ("Get Points"): the points packs, highest first, then the free points row.
struct GetPointsScreen: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<StoreScreenState>

    init(screen: AppScreenGetPoints, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.storeScreenModel(kind: .points, username: screen.username, productId: screen.productId), ownsModel: true))
    }

    private var model: StoreScreenModel { observed.model as! StoreScreenModel }

    var body: some View {
        let s = observed.state
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // RN shows the ribbon only for a username parameter (a link or the QR flow)
                if model.forSomeoneElse { UserRibbon(username: s.username).padding(.leading, 32).padding(.top, 8) }
                if s.loading {
                    ProgressView().frame(maxWidth: .infinity).padding(48).accessibilityIdentifier("store-loading")
                } else {
                    ForEach(s.rows, id: \.productId) { row in ProductItemLine(row: row, enabled: !s.processing) { model.buy(productId: $0) } }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
        }
        .background(theme.background)
        .navigationTitle(PurchaseCopy.shared.POINTS_TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .blocksBack(s.processing)
        .accessibilityIdentifier("get-points")
        .onAppear { model.start() }
        .modifier(StoreDialogs(observed: observed) { app.push(AppScreenSpinGame.shared) })
    }
}

/// RN accountBoostScreen ("Get Account Boost").
struct AccountBoostScreen: View {
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    @StateObject private var observed: Observed<StoreScreenState>

    init(screen: AppScreenAccountBoost, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.storeScreenModel(kind: .accountBoost, username: screen.username, productId: ""), ownsModel: true))
    }

    private var model: StoreScreenModel { observed.model as! StoreScreenModel }

    var body: some View {
        let s = observed.state
        ScrollView {
            if s.loading {
                ProgressView().frame(maxWidth: .infinity).padding(48).accessibilityIdentifier("store-loading")
            } else {
                VStack(spacing: 24) {
                    if !s.username.isEmpty { UserRibbon(username: s.username).frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 32).padding(.top, 8) }
                    VStack(spacing: 8) {
                        // README "Esie": the drawn mascot, not RN's old coin hugging picture
                        EsieView(.promo)
                        Text(PurchaseCopy.shared.BOOST_DESC).scaledFont(16).foregroundStyle(theme.secondary).multilineTextAlignment(.center).padding(.horizontal, typeSize.isAccessibilitySize ? 16 : 48)
                    }
                    VStack(spacing: 0) {
                        ForEach(s.rows, id: \.productId) { row in ProductItemLine(row: row, enabled: !s.processing) { model.buy(productId: $0) } }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 52)
                }
            }
        }
        .background(theme.background)
        .navigationTitle(PurchaseCopy.shared.BOOST_TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .blocksBack(s.processing)
        .accessibilityIdentifier("account-boost")
        .onAppear { model.start() }
        .modifier(StoreDialogs(observed: observed) {})
    }
}

/// The 5 spins pack on the spin game (RN spinGameView's ProductItemLine).
struct SpinPackRow: View {
    @StateObject private var observed: Observed<StoreScreenState>

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.storeScreenModel(kind: .spins, username: "", productId: ""), ownsModel: true))
    }

    private var model: StoreScreenModel { observed.model as! StoreScreenModel }

    var body: some View {
        let s = observed.state
        VStack(spacing: 0) {
            if s.loading { Color.clear.frame(height: 74) }
            ForEach(s.rows, id: \.productId) { row in ProductItemLine(row: row, enabled: !s.processing) { model.buy(productId: $0) } }
        }
        .padding(.horizontal, 16)
        .onAppear { model.start() }
        .modifier(StoreDialogs(observed: observed) {})
    }
}
