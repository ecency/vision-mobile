import EcencyCore
import SwiftUI

// RN wallet screens over the shared wallet models (WalletModel.kt, AssetDetailsModel.kt, TransferModel.kt,
// StakeModels.kt, WalletActionModels.kt, SendJournal.kt).

private let copy = WalletCopy.shared

/// RN walletScreen: estimated balance, quick actions, the restart journal's notices and the asset cards.
struct WalletTabView: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @StateObject private var wallet: Observed<WalletState>
    @StateObject private var journal: Observed<SendJournalState>
    @State private var receive = false

    init(env: AppEnvironment) {
        _wallet = StateObject(wrappedValue: Observed(env.wallet, ownsModel: false))
        _journal = StateObject(wrappedValue: Observed(env.sendJournal, ownsModel: false))
    }

    private var model: WalletModel { app.env.wallet }

    var body: some View {
        let s = wallet.state
        let user = session.state.username ?? ""
        let mine = s.account == user
        List {
            header(s, mine: mine).listRowSeparator(.hidden).listRowInsets(EdgeInsets())
            ForEach(journal.state.forAccount(account: user), id: \.actionId) { entry in
                JournalNoticeView(entry: entry) { app.env.sendJournal.dismiss(entry: entry) }.listRowSeparator(.hidden)
            }
            if s.showError {
                VStack(spacing: 6) {
                    Text(copy.REFRESH_FAILED).foregroundStyle(theme.text)
                    Button(copy.TRY_AGAIN) { model.retry() }.accessibilityIdentifier("wallet-retry")
                }
                .frame(maxWidth: .infinity).listRowSeparator(.hidden)
            }
            if s.showSkeleton { RowsSkeleton(count: 5, avatar: false, identifier: "wallet-skeleton").listRowSeparator(.hidden).listRowInsets(EdgeInsets()) }
            ForEach(mine ? s.rows : [], id: \.symbol) { row in
                AssetCardView(row: row) { app.push(AppScreenAssetDetails(symbol: row.symbol)) } onClaim: {
                    if row.pendingText == nil && row.symbol == "POINTS" { app.push(AppScreenCompanion.shared.of(route: .boost, arg: user)) }
                    else { model.claim(symbol: row.symbol) }
                }
                .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .refreshable { model.refresh(); app.env.sendJournal.recheck() }
        .onAppear { model.start(); app.env.sendJournal.start(); model.visible(shown: true) }
        .onDisappear { model.visible(shown: false) }
        .sheet(isPresented: $receive) { ReceiveSheet(username: user) }
        .accessibilityIdentifier("wallet-tab")
    }

    private func header(_ s: WalletState, mine: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // the refresh and manage buttons go under the total at accessibility sizes
            AdaptiveStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(copy.ESTIMATED_BALANCE).font(.footnote).foregroundStyle(theme.secondary).accessibilityHidden(true)
                    Text(mine ? s.totalText : "--.--").scaledFont(28, weight: .bold, relativeTo: .title).foregroundStyle(theme.text)
                        .lineLimit(1).minimumScaleFactor(0.5)
                        // "Estimated Balance" with the amount as its value (the caption above is hidden)
                        .accessibilityLabel(copy.ESTIMATED_BALANCE).accessibilityValue(mine ? s.totalText : "")
                        .accessibilityIdentifier("wallet-total")
                    Text(s.isUpdating ? copy.UPDATING : (s.updatedAtMillis.map { copy.updated(time: LocaleFormat.shared.time(epochMillis: $0.int64Value)) } ?? "--:--"))
                        .font(.caption).foregroundStyle(theme.secondary)
                }
                FormRowGap(minLength: 0)
                HStack(spacing: 0) {
                Button { model.refresh() } label: {
                    Group { if s.isUpdating { ProgressView() } else { Image(appIcon: .refresh).foregroundStyle(theme.icon) } }.tapTarget()
                }
                .buttonStyle(.borderless).accessibilityLabel(WalletUiCopy.shared.REFRESH).largeContent(WalletUiCopy.shared.REFRESH, appIcon: .refresh)
                .accessibilityIdentifier("wallet-refresh")
                Button { app.push(AppScreenAssetsSelect.shared) } label: { Image(appIcon: .tune).foregroundStyle(theme.icon).tapTarget() }
                    .buttonStyle(.borderless).accessibilityLabel(copy.MANAGE_ASSETS).largeContent(copy.MANAGE_ASSETS, appIcon: .tune)
                    .accessibilityIdentifier("wallet-manage")
                }
            }
            // one row, or two by two when the labels do not fit on one line each (largest text sizes, long translations)
            ViewThatFits(in: .horizontal) {
                let row = quickActions(wraps: false), grid = quickActions(wraps: true)
                HStack {
                    row[0]
                    Spacer()
                    row[1]
                    Spacer()
                    row[2]
                    Spacer()
                    row[3]
                }
                Grid(horizontalSpacing: 12, verticalSpacing: 12) {
                    GridRow { grid[0]; grid[1] }
                    GridRow { grid[2]; grid[3] }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(16)
        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12))
        .padding(16)
    }

    /// [wraps]: the grid's labels may take two lines (each word stays whole); the row's never wrap.
    private func quickActions(wraps: Bool) -> [AnyView] {
        [
            quick(.send, copy.SEND, "wallet-send", wraps) { app.push(AppScreenTransfer(transferType: "transfer", fund: "HIVE", layer: "hive", recipient: "")) },
            quick(.qrScan, copy.RECEIVE, "wallet-receive", wraps) { receive = true },
            quick(.swap, copy.SWAP, "wallet-swap", wraps) { app.push(AppScreenTrade(fund: "HIVE")) },
            quick(.add, copy.GET_POINTS, "wallet-get-points", wraps) { app.push(AppScreenCompanion.shared.of(route: .boost, arg: session.state.username ?? "")) },
        ]
    }

    private func quick(_ icon: AppIcon, _ label: String, _ id: String, _ wraps: Bool, _ action: @escaping () -> Void) -> AnyView {
        AnyView(Button(action: action) {
            VStack(spacing: 4) {
                Image(appIcon: icon, filled: true).foregroundStyle(.white).frame(width: 42, height: 42).background(Circle().fill(theme.accent))
                    .accessibilityHidden(true)
                // the row keeps each label on one line and turns into a grid instead of breaking a word
                Text(label).font(.caption).foregroundStyle(theme.text).multilineTextAlignment(.center)
                    .lineLimit(wraps ? 2 : 1).fixedSize(horizontal: !wraps, vertical: wraps)
            }
        }
        .buttonStyle(.borderless).accessibilityIdentifier(id))
    }
}

/// RN AssetCard.
struct AssetCardView: View {
    let row: AssetRow
    let onOpen: () -> Void
    let onClaim: () -> Void
    @Environment(\.appTheme) private var theme

    private var assetName: some View {
        HStack {
            AvatarView(url: row.item.iconUrl ?? "", size: 32)
            VStack(alignment: .leading) {
                Text(row.symbol).bold().foregroundStyle(theme.text).fixedSize()
                Text(row.name).font(.caption).foregroundStyle(theme.secondary).accessibilityIdentifier("asset-name")
            }
        }
    }

    private func balance(_ alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment) {
            // an amount stays on one line
            Text(row.balanceText).bold().foregroundStyle(theme.text).fixedSize()
                .accessibilityIdentifier("asset-balance-\(row.symbol)")
            if let fiat = row.fiatText { Text(fiat).font(.caption).foregroundStyle(theme.secondary).fixedSize().accessibilityIdentifier("asset-fiat") }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(action: onOpen) {
                // the balance beside the name, or under it when both do not fit (largest text sizes)
                ViewThatFits(in: .horizontal) {
                    HStack {
                        assetName
                        Spacer()
                        balance(.trailing)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        assetName
                        balance(.leading)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain).accessibilityIdentifier("asset-\(row.symbol)")
            // RN ClaimButton: the pill at its own width, centred in the card (claimContainer justifyContent center)
            if row.pendingText != nil || row.symbol == "POINTS" {
                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    claimButton
                    Spacer(minLength: 0)
                }
            }
        }
        .padding(14)
        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12))
    }

    private var claimButton: some View {
        Button(action: onClaim) {
            HStack(spacing: 6) {
                if row.claiming { ProgressView().controlSize(.small) } else { Image(appIcon: .add).accessibilityHidden(true) }
                Text(row.pendingText ?? copy.GET_POINTS).bold()
            }
            .font(.footnote).foregroundStyle(theme.accentText)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .overlay(Capsule().stroke(theme.accent))
            .frame(minHeight: minTapTarget)
        }
        .buttonStyle(.borderless).disabled(row.claiming).accessibilityIdentifier("claim-\(row.symbol)")
    }
}

/// The restart journal's notice: a spinner while the chain is asked, else what it said, with OK.
struct JournalNoticeView: View {
    let entry: JournalEntry
    let onDismiss: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack {
            if entry.outcome == nil { ProgressView().accessibilityIdentifier("journal-spinner") }
            Text(entry.message).font(.footnote).foregroundStyle(theme.text)
            Spacer()
            if entry.outcome != nil { Button(UiCopy.shared.OK, action: onDismiss).buttonStyle(.borderless).accessibilityIdentifier("journal-dismiss") }
        }
        .padding(12)
        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 10))
        .announces(entry.message, attempt: ObjectAttempt(entry), sticky: true)
        .accessibilityIdentifier("journal-\(entry.actionId)")
    }
}

/// A send that may already have gone through: "check your wallet" with a spinner, never a retry button.
struct SendPhaseView: View {
    let phase: SendPhase?
    let onClose: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        if phase is SendPhaseSending {
            ProgressView().frame(maxWidth: .infinity).accessibilityIdentifier("sending")
        } else if let checking = phase as? SendPhaseChecking {
            VStack(alignment: .leading, spacing: 8) {
                HStack { ProgressView().accessibilityIdentifier("check-spinner"); Text(copy.CHECKING).bold() }
                Text(checking.message).font(.footnote)
            }
            .foregroundStyle(theme.text).padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 10))
            .accessibilityElement(children: .combine).announces(checking.message, attempt: ObjectAttempt(checking)).accessibilityIdentifier("check-wallet")
        } else if let unconfirmed = phase as? SendPhaseUnconfirmed {
            VStack(alignment: .leading, spacing: 8) {
                Text(unconfirmed.message).font(.footnote).foregroundStyle(theme.text)
                Button(UiCopy.shared.CLOSE, action: onClose).accessibilityIdentifier("unconfirmed-close")
            }
            .padding(16).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 10))
            .announces(unconfirmed.message, attempt: ObjectAttempt(unconfirmed))
            .accessibilityIdentifier("unconfirmed")
        }
    }
}

/// RN receiveQrSheet.
struct ReceiveSheet: View {
    let username: String
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        // scrolls when large text needs more than the sheet's height
        ScrollView {
        VStack(spacing: 12) {
            Text(copy.RECEIVE).font(.headline).accessibilityAddTraits(.isHeader)
            Text(copy.RECEIVE_DESC).font(.footnote).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
            QrCodeView(text: ReceiveQr.shared.uri(username: username)).frame(width: 200, height: 200).padding(8).background(Color.white)
            Button("@\(username)") { UIPasteboard.general.string = username; _ = app.env.ui.toast(text: copy.COPIED) }
                .bold().accessibilityIdentifier("receive-copy")
        }
        .padding(24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("receive-sheet")
    }
}

/// One choice of chips over an asset's history, scrolling sideways (mirrored in RTL); [selected] nil when none applies.
struct HistoryChips: View {
    let chips: [(String, String)]
    let selected: String?
    let onSelect: (String) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(chips, id: \.0) { id, label in
                    let on = id == selected
                    Button { onSelect(id) } label: {
                        Text(label).scaledFont(14, weight: .bold)
                            .foregroundStyle(on ? Color.white : theme.secondary)
                            .fixedSize()
                            .padding(.horizontal, 14).padding(.vertical, 4)
                            .background(Capsule().fill(on ? theme.accent : Color.clear))
                            .frame(minHeight: minTapTarget)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                    .accessibilityIdentifier("history-chip-\(id)")
                }
            }
        }
        .listRowSeparator(.hidden)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(copy.HISTORY_CATEGORIES)
        .accessibilityIdentifier("history-chips")
    }
}

/// RN assetDetailsScreen.
struct AssetDetailsView: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<AssetDetailsState>
    @State private var sheet: DetailsSheet?
    @State private var filter = false
    @State private var analytics = false

    init(symbol: String, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.assetDetailsModel(symbol: symbol), ownsModel: true))
    }

    private var model: AssetDetailsModel { observed.model as! AssetDetailsModel }

    var body: some View {
        let s = observed.state
        List {
            summary(s).listRowSeparator(.hidden)
            if s.symbol == "POINTS" {
                // RN HorizontalIconList of POINTS_KEYS: the ways to earn points
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(copy.POINTS_EARN, id: \.name) { way in
                            Button { _ = app.env.ui.toast(text: way.description_) } label: {
                                VStack { Text(way.points).bold().foregroundStyle(theme.accentText); Text(way.name).font(.caption).foregroundStyle(theme.text) }
                                    .padding(10).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 10))
                            }
                            .buttonStyle(.plain)
                            .accessibilityElement(children: .combine)
                            .accessibilityHint(way.description_)
                        }
                    }
                }
                .listRowSeparator(.hidden).accessibilityIdentifier("points-earn")
            }
            if !s.pending.isEmpty {
                Section(copy.PENDING_REQUESTS) {
                    ForEach(s.pending, id: \.key) { row in ActivityRowView(row: row, pending: true, expanded: s.expanded.contains(row.key), busy: s.busyKey == "order:\(row.trxIndex ?? 0)", model: model) }
                }
            }
            if s.activitiesEnabled {
                Section(copy.ACTIVITIES) {
                    // #2747: web's categories (hive layer, the server filters by their bitmask) and points types
                    if !s.categories.isEmpty {
                        HistoryChips(chips: s.categories.map { ($0.name, $0.label) }, selected: s.category?.name) { name in
                            if let category = s.categories.first(where: { $0.name == name }) { model.setCategory(category: category) }
                        }
                    }
                    if !s.pointTypes.isEmpty {
                        HistoryChips(chips: s.pointTypes.map { (String($0.int32Value), copy.pointTypeLabel(type: $0.int32Value)) }, selected: String(s.pointType)) { id in
                            if let type = Int32(id) { model.setPointType(type: type) }
                        }
                    }
                    ForEach(s.activities, id: \.key) { row in
                        ActivityRowView(row: row, pending: false, expanded: s.expanded.contains(row.key), busy: false, model: model)
                            .onAppear { if row.key == s.activities.last?.key { model.loadMore() } }
                    }
                    Group {
                        if s.isLoading || s.isLoadingMore { ProgressView() }
                        else if s.historyFailed { Text(copy.ACTIVITIES_FAILED).accessibilityIdentifier("history-failed") }
                        else if s.isEmpty { Text(copy.NO_ACTIVITIES).accessibilityIdentifier("history-empty") }
                    }
                    .font(.footnote).foregroundStyle(theme.secondary).frame(maxWidth: .infinity)
                }
            }
        }
        .listStyle(.plain)
        .refreshable { model.refresh() }
        .navigationTitle(copy.DETAILS).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if s.filterable {
                Button { filter = true } label: { Image(appIcon: .filter) }
                    .accessibilityLabel(copy.FILTER_TITLE).accessibilityIdentifier("history-filter")
            }
        }
        .onAppear { model.start() }
        .alert(copy.CONFIRM, isPresented: Binding(get: { s.prompt != nil }, set: { if !$0 { model.dismissPrompt() } })) {
            Button(copy.CONFIRM) { model.confirmPrompt() }.accessibilityIdentifier("details-confirm")
            Button(copy.CANCEL, role: .cancel) { model.dismissPrompt() }
        } message: {
            if let p = s.prompt as? DetailsPromptDeleteRecurrent { Text(RecurrentCopy.shared.unsubscribe(username: p.schedule.to)) } else { Text(L.Native.Ios.Wallet.cancelOrder) }
        }
        .sheet(item: Binding(get: { sheet.map(IdentifiedSheet.init) }, set: { sheet = $0?.sheet })) { item in
            switch item.sheet {
            case .recurrentTransfers: RecurrentSheet(list: s.recurrent) { model.deleteRecurrent(schedule: $0) }
            default: if let account = s.account { DelegationsSheet(env: app.env, account: account, sheet: item.sheet) { name in sheet = nil; app.push(AppScreenTransfer(transferType: "delegate_vesting_shares", fund: "HP", layer: "hive", recipient: name)) } }
            }
        }
        .sheet(isPresented: $filter) { HistoryFilterSheet(symbol: s.symbol, selected: s.filter) { model.setFilter(ops: $0); filter = false } }
        .sheet(isPresented: $analytics) {
            if let coin = s.analyticsCoin, let user = s.account { BalanceAnalyticsSheet(env: app.env, username: user, coinType: coin) }
        }
        .accessibilityIdentifier("asset-details")
    }

    private func summary(_ s: AssetDetailsState) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                AvatarView(url: s.asset?.iconUrl ?? "", size: 36)
                Text(s.symbol).font(.title3).bold().foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                Spacer()
                if let apr = s.aprText { Text(apr).font(.footnote).foregroundStyle(theme.accentText).accessibilityIdentifier("asset-apr") }
                // RN coinBasics: the chart-line button opens the balance analytics (HIVE, HBD, HP)
                if s.analyticsCoin != nil, s.account != nil {
                    Button { analytics = true } label: { Image(appIcon: .chartLine).foregroundStyle(theme.icon).tapTarget() }
                        .buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.OPEN_ANALYTICS).largeContent(UiCopy.shared.OPEN_ANALYTICS, appIcon: .chartLine).accessibilityIdentifier("open-analytics")
                }
            }
            ForEach(s.summary, id: \.label) { r in
                Button { if let o = r.opens { sheet = o } } label: {
                    // label and value stack at accessibility sizes instead of squeezing each other
                    AdaptiveStack { Text(r.label).foregroundStyle(r.opens != nil ? theme.accentText : theme.secondary); FormRowGap(minLength: 0); Text(r.value).bold().foregroundStyle(theme.text) }
                        .font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.borderless).disabled(r.opens == nil)
                .accessibilityElement(children: .combine)
                .accessibilityRemoveTraits(r.opens == nil ? .isButton : [])
                .accessibilityIdentifier("summary-\(r.label)")
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100))], spacing: 8) {
                ForEach(s.actions, id: \.actionName) { a in
                    Button(a.label) { app.push(a.screen) }.buttonStyle(.borderedProminent).tint(theme.accent).font(.footnote)
                        .accessibilityIdentifier("action-\(a.actionName)")
                }
            }
        }
        .padding(16).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct IdentifiedSheet: Identifiable {
    let sheet: DetailsSheet
    var id: String { sheet.name }
}

/// RN Transaction row.
struct ActivityRowView: View {
    let row: CoinActivity
    let pending: Bool
    let expanded: Bool
    let busy: Bool
    let model: AssetDetailsModel
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                // what, when and how much: one VoiceOver stop that expands the row; the value goes under the
                // name at accessibility sizes
                AdaptiveStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(copy.label(key: row.textKey)).bold().foregroundStyle(theme.text)
                        Text(subtitle).font(.caption).foregroundStyle(theme.secondary)
                    }
                    FormRowGap(minLength: 0)
                    Text(row.displayValue).font(.footnote).foregroundStyle(theme.text)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(expanded ? [.isButton, .isSelected] : .isButton)
                .accessibilityAction { model.toggle(key: row.key) }
                if row.cancelable {
                    if busy { ProgressView() } else {
                        Button { if let i = row.trxIndex { model.cancelOrder(orderId: i.int64Value) } } label: { Image(appIcon: .close).foregroundStyle(theme.dangerText).tapTarget() }
                            .buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.CANCEL).accessibilityIdentifier("cancel-order")
                    }
                }
                if row.repeatable && !pending {
                    Button { if let screen = model.repeat(row: row) { app.push(screen) } } label: { Image(appIcon: .repeat).foregroundStyle(theme.accentText).tapTarget() }
                        .buttonStyle(.borderless).accessibilityLabel(WalletUiCopy.shared.REPEAT).accessibilityIdentifier("repeat-\(row.key)")
                }
            }
            if expanded {
                if let d = row.details { Text(d).font(.caption).foregroundStyle(theme.secondary) }
                if let m = row.memo, !m.isEmpty { Text(m).font(.caption).foregroundStyle(theme.text) }
                if let id = row.trxId {
                    Button("\(copy.TRANSACTION_ID): \(bidiValue("\(id.prefix(12))..."))") {
                        UIPasteboard.general.string = TransactionExplorer.shared.link(trxId: id)
                        _ = app.env.ui.toast(text: copy.COPIED)
                    }
                    .font(.caption).buttonStyle(.borderless).accessibilityIdentifier("copy-tx")
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { model.toggle(key: row.key) }
        .accessibilityIdentifier("activity-\(row.key)")
    }

    private var subtitle: String {
        if row.textKey == "recurrent_transfer", let e = row.executions, (Int(e) ?? 0) > 0 {
            return RecurrentCopy.shared.scheduleSummary(executions: e, hours: row.recurrence ?? "")
        }
        if row.textKey == "fill_recurrent_transfer" { return RecurrentCopy.shared.remaining(executions: Int32(row.executions ?? "") ?? 0) }
        let time = (row.expires?.isEmpty == false ? row.expires : row.created) ?? ""
        return (pending ? "\(copy.EXPIRES) " : "") + Self.relative(time)
    }

    static func relative(_ time: String) -> String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        let date = iso.date(from: time) ?? plain.date(from: time) ?? plain.date(from: String(time.prefix(19)) + "Z")
        guard let date else { return time }
        // "2 hours ago" in the app language (shared FromNow, as Android)
        return FromNow.shared.format(epochMillis: Int64(date.timeIntervalSince1970 * 1000), nowMillis: Int64(Date().timeIntervalSince1970 * 1000))
    }
}

/// RN walletHistoryFiltersSheet.
struct HistoryFilterSheet: View {
    let symbol: String
    let onApply: ([String]?) -> Void
    @State private var picked: Set<String>
    private let all: [String]

    init(symbol: String, selected: [String]?, onApply: @escaping ([String]?) -> Void) {
        self.symbol = symbol
        self.onApply = onApply
        all = WalletHistory.shared.opsFor(symbol: symbol)
        _picked = State(initialValue: Set(selected ?? all))
    }

    var body: some View {
        NavigationStack {
            List {
                Text(copy.FILTER_DESC).font(.footnote)
                Button(picked.count == all.count ? copy.FILTER_NONE : copy.FILTER_ALL) { picked = picked.count == all.count ? [] : Set(all) }
                ForEach(all, id: \.self) { op in
                    Button { if picked.contains(op) { picked.remove(op) } else { picked.insert(op) } } label: {
                        HStack { Image(appIcon: .checkbox, filled: picked.contains(op)).accessibilityHidden(true); Text(copy.label(key: op).trimmingCharacters(in: .whitespaces)) }
                    }
                    .accessibilityAddTraits(picked.contains(op) ? .isSelected : [])
                    .accessibilityIdentifier("filter-\(op)")
                }
            }
            .navigationTitle(copy.FILTER_TITLE).navigationBarTitleDisplayMode(.inline)
            .toolbar { Button(copy.FILTER_APPLY) { onApply(all.filter { picked.contains($0) }) }.disabled(picked.isEmpty).accessibilityIdentifier("filter-apply") }
        }
    }
}

/// RN recurrentTransfersModal.
struct RecurrentSheet: View {
    let list: [RecurrentSchedule]
    let onDelete: (RecurrentSchedule) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        NavigationStack {
            List(list, id: \.to) { r in
                AdaptiveStack {
                    VStack(alignment: .leading) {
                        Text(r.to).bold()
                        Text(RecurrentCopy.shared.transferIn(time: ActivityRowView.relative(r.triggerDate))).font(.caption).foregroundStyle(theme.secondary)
                    }
                    FormRowGap()
                    VStack(alignment: .trailing) {
                        Text(r.amount)
                        Text(RecurrentCopy.shared.remaining(executions: r.remainingExecutions)).font(.caption).foregroundStyle(theme.secondary)
                    }
                    Button { onDelete(r) } label: { Image(appIcon: .close).foregroundStyle(theme.dangerText).tapTarget() }.buttonStyle(.borderless).accessibilityLabel(UiCopy.shared.DELETE)
                        .accessibilityIdentifier("recurrent-delete-\(r.to)")
                }
            }
            .navigationTitle(RecurrentCopy.shared.title(total: Int32(list.count))).navigationBarTitleDisplayMode(.inline)
        }
        .accessibilityIdentifier("recurrent-sheet")
    }
}

/// RN delegationsModal.
struct DelegationsSheet: View {
    let onUpdate: (String) -> Void
    @StateObject private var observed: Observed<DelegationsState>
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment, account: String, sheet: DetailsSheet, onUpdate: @escaping (String) -> Void) {
        self.onUpdate = onUpdate
        _observed = StateObject(wrappedValue: Observed(env.delegationsModel(account: account, sheet: sheet), ownsModel: true))
    }

    private var model: DelegationsModel { observed.model as! DelegationsModel }

    var body: some View {
        let s = observed.state
        NavigationStack {
            List(s.visible, id: \.account) { d in
                AdaptiveStack {
                    VStack(alignment: .leading) {
                        Text(d.expiring ? copy.EXPIRING : d.account).bold().foregroundStyle(d.expiring ? theme.dangerText : theme.text)
                        if let t = d.timeLeft ?? d.date.map({ String($0.prefix(10)) }) { Text(t).font(.caption).foregroundStyle(theme.secondary) }
                    }
                    FormRowGap()
                    Button { if d.updatable { onUpdate(d.account) } } label: {
                        VStack(alignment: .trailing) {
                            Text(d.hp).foregroundStyle(d.expiring ? theme.dangerText : theme.text)
                            if d.updatable { Text(copy.TAP_UPDATE).font(.caption2).foregroundStyle(theme.accentText) }
                        }
                    }
                    .buttonStyle(.borderless).disabled(!d.updatable)
                }
                .accessibilityIdentifier("delegation-\(d.account)")
            }
            .overlay { if s.isLoading { ProgressView() } }
            .searchable(text: Binding(get: { s.query }, set: { model.setQuery(q: $0) }), prompt: copy.SEARCH)
            .navigationTitle(s.title).navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { model.start() }
        .accessibilityIdentifier("delegations-sheet")
    }
}
