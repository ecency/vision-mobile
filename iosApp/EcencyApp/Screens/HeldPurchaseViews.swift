import EcencyCore
import SwiftUI
import UIKit

/// The purchase service's held orders, mirrored for SwiftUI (`PurchaseService.watch`), for as long as the view lives.
@MainActor
final class HeldPurchases: ObservableObject {
    @Published private(set) var orders: [HeldPurchase] = []
    private var watcher: Closeable?

    init(_ purchases: PurchaseService) {
        orders = purchases.current.heldOrders
        watcher = purchases.watch { [weak self] s in
            MainActor.assumeIsolated {
                guard let self, self.orders != s.heldOrders else { return }
                self.orders = s.heldOrders
            }
        }
    }

    deinit { watcher?.close() }
}

/// Purchases Ecency holds for review (issue #3612): one notice per order with its number, "Contact support" (a mail
/// naming the order) and "Dismiss". On Perks and in Settings until a resend is accepted or the user dismissed it.
struct HeldPurchaseNotices: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @StateObject private var held: HeldPurchases

    init(env: AppEnvironment) {
        _held = StateObject(wrappedValue: HeldPurchases(env.purchases))
    }

    private var copy: HeldPurchaseCopy { HeldPurchaseCopy.shared }

    var body: some View {
        ForEach(held.orders, id: \.order) { order in
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Image(appIcon: .error).accessibilityHidden(true)
                    Text(copy.TITLE).scaledFont(15, weight: .bold).accessibilityAddTraits(.isHeader)
                }
                Text(copy.message(order: order.order)).scaledFont(14)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("held-purchase-text-\(order.order)")
                HStack(spacing: 16) {
                    Spacer(minLength: 0)
                    Button(copy.DISMISS) { app.env.purchases.dismissHeld(order: order.order) }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier("held-purchase-dismiss-\(order.order)")
                    Button { contactSupport(order.order) } label: { Text(copy.CONTACT_SUPPORT).bold() }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier("held-purchase-support-\(order.order)")
                }
                .padding(.top, 4)
            }
            .foregroundStyle(theme.warningText)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.warningBackground)
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.warningBorder, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("held-purchase-\(order.order)")
        }
    }

    /// A mail to support naming the order; without a mail app, the feedback mail's failure toast.
    private func contactSupport(_ order: String) {
        guard let url = URL(string: copy.supportMailto(order: order)) else { return }
        let env = app.env
        UIApplication.shared.open(url, options: [:]) { opened in
            if !opened { env.ui.toast(text: SettingsCopy.shared.FEEDBACK_FAIL) }
        }
    }
}

/// Settings' form section of the held purchase notices; nothing (no empty section) without one.
struct HeldPurchaseSection: View {
    @StateObject private var held: HeldPurchases
    private let env: AppEnvironment

    init(env: AppEnvironment) {
        self.env = env
        _held = StateObject(wrappedValue: HeldPurchases(env.purchases))
    }

    var body: some View {
        if !held.orders.isEmpty {
            Section {
                HeldPurchaseNotices(env: env)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
        }
    }
}
