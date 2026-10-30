import EcencyCore
import SwiftUI

private let copy = WalletCopy.shared
private let tcopy = TransferCopy.shared
private let scopy = StakeCopy.shared

/// RN TransferScreen: the view its transfer type asks for.
struct TransferRouteView: View {
    let screen: AppScreenTransfer
    @EnvironmentObject private var app: AppContainer

    var body: some View {
        switch TransferView.companion.of(screen: screen) {
        case .transfer: if let m = app.env.transferModel(screen: screen) { TransferFormView(model: m) }
        case .delegate: DelegateFormView(model: app.env.delegateModel(referredUsername: screen.recipient))
        case .powerDown: PowerDownFormView(model: app.env.powerDownModel(routesOnly: false))
        case .routes: PowerDownFormView(model: app.env.powerDownModel(routesOnly: true))
        case .receive: ReceiveAddressView(screen: screen)
        default: ComingSoonView(title: copy.label(key: screen.transferType))
        }
    }
}

/// Pops the screen when its model says it is done.
private struct CloseOn: ViewModifier {
    let closed: Bool
    @Environment(\.dismiss) private var dismiss

    func body(content: Content) -> some View {
        content.onChange(of: closed) { _, done in if done { dismiss() } }
    }
}

private struct WalletField: View {
    let label: String
    @Binding var text: String
    var number = false
    var disabled = false
    /// Left to right whatever the app's direction (accounts, amounts, permlinks); a memo is free text.
    var machine = true
    let id: String

    var body: some View {
        TextField(label, text: $text)
            .keyboardType(number ? .decimalPad : .default)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .textFieldStyle(.roundedBorder).disabled(disabled)
            .environment(\.layoutDirection, machine ? .leftToRight : layoutDirection)
            .accessibilityIdentifier(id)
    }

    @Environment(\.layoutDirection) private var layoutDirection
}

private func binding(_ value: String, _ set: @escaping (String) -> Void) -> Binding<String> { Binding(get: { value }, set: set) }

/// RN TransferView.
struct TransferFormView: View {
    @EnvironmentObject private var app: AppContainer
    @State private var favorites = false
    @State private var scanning = false
    @StateObject private var observed: Observed<TransferState>
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    init(model: TransferModel) {
        _observed = StateObject(wrappedValue: Observed(model, ownsModel: true))
    }

    private var model: TransferModel { observed.model as! TransferModel }

    var body: some View {
        let s = observed.state
        Form {
            recipientSection(s)
            amountSection(s)
            if s.canSchedule { scheduleSection(s) }
            if s.showMemo { memoSection(s) }
            notes(s)
            SendPhaseView(phase: s.phase) { dismiss() }
            if !s.busy && !(s.phase is SendPhaseUnconfirmed) {
                Button(tcopy.NEXT) { model.next() }.frame(maxWidth: .infinity).disabled(!s.nextEnabled).accessibilityIdentifier("transfer-next")
            }
        }
        .navigationTitle(s.kind.title).navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .modifier(CloseOn(closed: s.closed))
        .alert(alertTitle(s.prompt), isPresented: Binding(get: { s.prompt != nil }, set: { if !$0 { model.dismissPrompt() } })) {
            if s.prompt is TransferPromptConfirm {
                Button(copy.CONFIRM) { model.confirm() }.accessibilityIdentifier("transfer-confirm")
                Button(copy.CANCEL, role: .cancel) { model.dismissPrompt() }
            } else if s.prompt is TransferPromptConfirmDeleteSchedule {
                Button(copy.CONFIRM) { model.confirmDeleteSchedule() }.accessibilityIdentifier("delete-schedule-confirm")
                Button(copy.CANCEL, role: .cancel) { model.dismissPrompt() }
            } else {
                Button(UiCopy.shared.OK) { model.dismissPrompt() }
            }
        } message: {
            if let a = s.prompt as? TransferPromptAlert { Text(a.message) }
        }
        .sheet(isPresented: $favorites) { FavoritesSheet(env: app.env) { model.setRecipient(text: $0); favorites = false } }
        .fullScreenCover(isPresented: $scanning) {
            RecipientScanner(env: app.env, onValue: { model.scanned(value: $0); scanning = false }, onClose: { scanning = false })
        }
        .modifier(LeaveGuard(busy: s.busy))
        .accessibilityIdentifier("transfer-form")
    }

    @ViewBuilder private func recipientSection(_ s: TransferState) -> some View {
        Section {
            Text("\(tcopy.FROM): \(bidiValue("@" + (s.from ?? "")))").font(.footnote).foregroundStyle(theme.secondary)
            if !s.recipientLocked {
                WalletField(label: tcopy.TO_PLACEHOLDER, text: binding(s.recipient) { model.setRecipient(text: $0) }, disabled: s.busy, id: "transfer-to")
                HStack {
                    Button(copy.PICK_FAVORITE) { favorites = true }.font(.footnote).disabled(s.busy).accessibilityIdentifier("transfer-favorites")
                    Spacer()
                    // RN _openQrScanner: a code's account becomes the recipient (TransferRecipientParser)
                    Button { scanning = true } label: { Image(appIcon: .qrScan).tapTarget() }
                        .buttonStyle(.borderless).disabled(s.busy)
                        .accessibilityLabel(WalletUiCopy.shared.SCAN_QR_RECIPIENT)
                        .largeContent(WalletUiCopy.shared.SCAN_QR_RECIPIENT, appIcon: .qrScan)
                        .accessibilityIdentifier("transfer-scan")
                }
                ForEach(s.suggestions, id: \.self) { (n: String) in Button("@\(n)") { model.pickSuggestion(name: n) }.accessibilityIdentifier("suggestion-\(n)") }
            }
            if s.badActor { Text(tcopy.BAD_ACTOR).font(.footnote).foregroundStyle(theme.dangerText).announces(tcopy.BAD_ACTOR, debounce: true).accessibilityIdentifier("bad-actor") }
            if s.tooManyRecipients { Text(tcopy.ONE_RECIPIENT).font(.footnote).foregroundStyle(theme.dangerText).announces(tcopy.ONE_RECIPIENT, debounce: true).accessibilityIdentifier("one-recipient") }
            if !s.tokenChoices.isEmpty {
                Picker(tcopy.SELECT_TOKEN, selection: binding(s.fund) { model.setFund(symbol: $0) }) {
                    ForEach(s.tokenChoices, id: \.self) { (t: String) in Text(t).tag(t) }
                }
                .pickerStyle(.segmented).accessibilityIdentifier("transfer-fund")
            }
        }
    }

    @ViewBuilder private func amountSection(_ s: TransferState) -> some View {
        Section {
            WalletField(label: "\(tcopy.AMOUNT) (\(s.displayFund))", text: binding(s.amount) { model.setAmount(text: $0) }, number: true, disabled: s.busy, id: "transfer-amount")
            AdaptiveStack {
                let balanceText: String = s.balanceFailed ? tcopy.BALANCE_FAILED : "\(tcopy.BALANCE) \(s.balance ?? "...") \(s.displayFund)"
                Button { if s.balanceFailed { model.retryBalance() } } label: { Text(balanceText).multilineTextAlignment(.leading) }
                    .font(.footnote).foregroundStyle(s.balanceFailed ? theme.dangerText : theme.secondary).buttonStyle(.borderless)
                    .multilineTextAlignment(.leading)
                    .accessibilityIdentifier("transfer-balance")
                FormRowGap()
                Button(tcopy.MAX) { model.max() }.buttonStyle(.borderless).disabled(s.balance == nil || s.busy).accessibilityIdentifier("transfer-max")
            }
            if s.precisionFailed { Button(tcopy.PRECISION_FAILED) { model.retryPrecision() }.font(.footnote).foregroundStyle(theme.dangerText) }
        }
    }

    @ViewBuilder private func scheduleSection(_ s: TransferState) -> some View {
        let hours: Int32 = s.recurrenceHours?.int32Value ?? 0
        let custom: Bool = hours != 0 && !Recurrence.entries.contains(where: { $0.hours == hours })
        Section {
            Picker(tcopy.SCHEDULE, selection: Binding<Int32>(get: { hours }, set: { (v: Int32) in model.setRecurrence(hours: v == 0 ? nil : KotlinInt(value: v)) })) {
                Text(tcopy.ONE_TIME).tag(Int32(0))
                ForEach(Recurrence.entries, id: \.hours) { (r: Recurrence) in Text(r.label).tag(r.hours) }
                if custom { Text(tcopy.customSchedule(hours: hours)).tag(hours) }
            }
            .accessibilityIdentifier("transfer-schedule")
            if s.isRecurrent {
                WalletField(label: tcopy.EXECUTIONS_PLACEHOLDER, text: binding(s.executions) { model.setExecutions(text: $0) }, number: true, disabled: s.busy, id: "transfer-executions")
            }
            if let date = s.existingScheduleDate {
                Button(tcopy.DELETE_RECURRENT + String(date.prefix(10))) { model.deleteSchedule() }.foregroundStyle(theme.dangerText).accessibilityIdentifier("delete-schedule")
            }
        }
    }

    @ViewBuilder private func memoSection(_ s: TransferState) -> some View {
        Section(footer: Text(tcopy.MEMO_DESC)) {
            WalletField(label: tcopy.MEMO_PLACEHOLDER, text: binding(s.memo) { model.setMemo(text: $0) }, disabled: s.busy, machine: false, id: "transfer-memo")
        }
    }

    @ViewBuilder private func notes(_ s: TransferState) -> some View {
        if s.kind == TransferKind.convert { Text(tcopy.CONVERT_DESC).font(.footnote).foregroundStyle(theme.secondary) }
        if let ex = s.exchange {
            if s.exchangeMemoRequired { Text(tcopy.exchangeMemoRequired(exchange: ex)).font(.footnote).foregroundStyle(theme.dangerText).announces(tcopy.exchangeMemoRequired(exchange: ex), debounce: true).accessibilityIdentifier("exchange-memo") }
            if s.exchangeRecurrentWarning { Text(tcopy.exchangeRecurrentWarning(exchange: ex)).font(.footnote).foregroundStyle(theme.dangerText).announces(tcopy.exchangeRecurrentWarning(exchange: ex), debounce: true) }
        }
    }

    private func alertTitle(_ p: TransferPrompt?) -> String {
        if let a = p as? TransferPromptAlert { return a.title }
        if p is TransferPromptConfirmDeleteSchedule { return tcopy.DELETE_RECURRENT.trimmingCharacters(in: .whitespaces) + "?" }
        return tcopy.INFORMATION
    }
}

/// Alerts of the delegate and power down screens.
private struct StakeAlertModifier: ViewModifier {
    let prompt: StakePrompt?
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    func body(content: Content) -> some View {
        content.alert(title, isPresented: Binding(get: { prompt != nil }, set: { if !$0 { onDismiss() } })) {
            if prompt is StakePromptAlert {
                Button(UiCopy.shared.OK, action: onDismiss)
            } else {
                Button(copy.CONFIRM, action: onConfirm).accessibilityIdentifier("stake-confirm")
                Button(copy.CANCEL, role: .cancel, action: onDismiss)
            }
        } message: {
            if let a = prompt as? StakePromptAlert { Text(a.message) }
            else if let r = prompt as? StakePromptReview { Text(r.summary + (r.overwrite.map { "\n\n" + $0 } ?? "")) }
        }
    }

    private var title: String {
        switch prompt {
        case let a as StakePromptAlert: return a.title
        case is StakePromptReview: return scopy.CONFIRM_TITLE
        case is StakePromptConfirmStop: return scopy.STOP_INFORMATION
        case let r as StakePromptConfirmRemoveRoute: return "\(scopy.WITHDRAW_ACCOUNTS): \(bidiValue("@" + r.account))?"
        default: return tcopy.INFORMATION
        }
    }
}

/// RN delegateScreen.
struct DelegateFormView: View {
    @StateObject private var observed: Observed<DelegateState>
    @Environment(\.appTheme) private var theme

    init(model: DelegateModel) {
        _observed = StateObject(wrappedValue: Observed(model, ownsModel: true))
    }

    private var model: DelegateModel { observed.model as! DelegateModel }

    var body: some View {
        let s = observed.state
        Form {
            Section {
                WalletField(label: tcopy.TO_PLACEHOLDER, text: binding(s.recipient) { model.setRecipient(text: $0) }, disabled: s.busy, id: "delegate-to")
                ForEach(s.suggestions, id: \.self) { n in Button("@\(n)") { model.pickSuggestion(name: n) } }
                if s.badActor { Text(tcopy.BAD_ACTOR).font(.footnote).foregroundStyle(theme.dangerText).announces(tcopy.BAD_ACTOR, debounce: true) }
                if let hp = s.existingHp { Text("\(scopy.ALREADY_DELEGATED)\(bidiValue("@" + s.recipient))  \(bidiValue("\(hp) HP"))").font(.footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("already-delegated") }
            }
            Section {
                WalletField(label: scopy.AMOUNT_HP, text: binding(s.hp) { model.setHp(text: $0) }, number: true, disabled: s.busy, id: "delegate-hp")
                AdaptiveStack {
                    Text("\(scopy.REMAIN_HP): \(bidiValue(s.remainingHp))").font(.footnote).foregroundStyle(theme.secondary)
                    FormRowGap()
                    Button(tcopy.MAX) { model.max() }.buttonStyle(.borderless).disabled(s.busy)
                }
                Slider(value: Binding(get: { min(1, (Double(s.hp) ?? 0) / max(s.maxHp, 0.001)) }, set: { model.setFraction(f: $0) })).disabled(s.busy)
            }
            SendPhaseView(phase: s.phase) {}
            if !s.busy { Button(scopy.REVIEW) { model.review() }.frame(maxWidth: .infinity).disabled(!s.reviewEnabled).accessibilityIdentifier("delegate-review") }
        }
        .navigationTitle(scopy.DELEGATE).navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .modifier(CloseOn(closed: s.closed))
        .modifier(StakeAlertModifier(prompt: s.prompt, onConfirm: { model.confirm() }, onDismiss: { model.dismissPrompt() }))
        .modifier(LeaveGuard(busy: s.busy))
        .accessibilityIdentifier("delegate-form")
    }
}

/// RN powerDownScreen and its withdraw routes.
struct PowerDownFormView: View {
    @StateObject private var observed: Observed<PowerDownState>
    @Environment(\.appTheme) private var theme
    @State private var routeName = ""
    @State private var routePercent = "25"
    @State private var autoVest = false

    init(model: PowerDownModel) {
        _observed = StateObject(wrappedValue: Observed(model, ownsModel: true))
    }

    private var model: PowerDownModel { observed.model as! PowerDownModel }

    var body: some View {
        let s = observed.state
        Form {
            if !s.routesOnly {
                Section {
                    if s.poweringDown {
                        Text(scopy.POWERING_DOWN).bold()
                        Text(scopy.POWERING_DOWN_SUB).font(.footnote).foregroundStyle(theme.secondary)
                        if let info = s.nextInfo { Text(info).font(.footnote).accessibilityIdentifier("next-power-down") }
                        if !s.busy { Button(scopy.STOP, role: .destructive) { model.stop() }.accessibilityIdentifier("power-down-stop") }
                    } else if s.loaded {
                        WalletField(label: scopy.AMOUNT_HP, text: binding(s.hp) { model.setHp(text: $0) }, number: true, disabled: s.busy, id: "power-down-hp")
                        AdaptiveStack {
                            Text("\(scopy.ESTIMATED_WEEKLY): \(scopy.weekly(hive: s.weekly))").font(.footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("weekly")
                            FormRowGap()
                            Button(tcopy.MAX) { model.max() }.buttonStyle(.borderless)
                        }
                        Slider(value: Binding(get: { min(1, (Double(s.hp) ?? 0) / max(s.maxHp, 0.001)) }, set: { model.setFraction(f: $0) })).disabled(s.busy)
                        if !s.busy { Button(tcopy.NEXT) { model.next() }.disabled(!s.amountValid).accessibilityIdentifier("power-down-next") }
                    } else {
                        ProgressView()
                    }
                    SendPhaseView(phase: s.phase) {}
                }
            }
            if s.showRoutes {
                Section(scopy.WITHDRAW_ACCOUNTS) {
                    ForEach(s.routes, id: \.toAccount) { r in
                        AdaptiveStack {
                            Text("@\(r.toAccount)")
                            FormRowGap()
                            Text(LocaleFormat.shared.percent(value: Double(r.percent) / 100, maxFraction: 1, minFraction: 1) + (r.autoVest ? " HP" : "")).foregroundStyle(theme.secondary)
                            Button { model.removeRoute(account: r.toAccount) } label: { Image(appIcon: .close).foregroundStyle(theme.dangerText).tapTarget() }.accessibilityLabel(UiCopy.shared.REMOVE)
                                .buttonStyle(.borderless).disabled(s.routeBusy).accessibilityIdentifier("route-remove-\(r.toAccount)")
                        }
                    }
                    WalletField(label: tcopy.TO_PLACEHOLDER, text: $routeName, disabled: s.routeBusy, id: "route-account")
                    WalletField(label: scopy.PERCENT, text: $routePercent, number: true, disabled: s.routeBusy, id: "route-percent")
                    Toggle(scopy.AUTO_VESTS, isOn: $autoVest)
                    if s.routeBusy { ProgressView() } else {
                        Button(UiCopy.shared.SAVE_CAPS) { model.addRoute(account: routeName, percent: Int32(routePercent) ?? 0, autoVest: autoVest); routeName = "" }
                            .disabled(routeName.isEmpty || !(1...100).contains(Int(routePercent) ?? 0)).accessibilityIdentifier("route-save")
                    }
                }
            }
        }
        .navigationTitle(s.routesOnly ? scopy.WITHDRAW_ACCOUNTS : scopy.POWER_DOWN).navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .modifier(CloseOn(closed: s.closed))
        .modifier(StakeAlertModifier(prompt: s.prompt, onConfirm: { model.confirm() }, onDismiss: { model.dismissPrompt() }))
        .modifier(LeaveGuard(busy: s.busy))
        .accessibilityIdentifier("power-down-form")
    }
}

/// RN AddressView.
struct ReceiveAddressView: View {
    let screen: AppScreenTransfer
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 16) {
            if screen.recipient.isEmpty {
                Text(copy.ADDRESS_UNAVAILABLE).foregroundStyle(theme.secondary)
            } else {
                QrCodeView(text: screen.recipient).frame(width: 200, height: 200).padding(8).background(Color.white)
                Text(copy.addressLabel(symbol: screen.fund)).font(.footnote).foregroundStyle(theme.secondary)
                Button(screen.recipient) { UIPasteboard.general.string = screen.recipient; _ = app.env.ui.toast(text: copy.COPIED) }
                    .font(.footnote).padding(12).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8)).accessibilityIdentifier("address-copy")
            }
            Spacer()
        }
        .padding(24)
        .navigationTitle("\(copy.RECEIVE) · \(screen.fund)").navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("receive-address")
    }
}

/// RN TradeScreen.
struct TradeView: View {
    @StateObject private var observed: Observed<TradeState>
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    private let tr = TradeCopy.shared

    init(fund: String, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.tradeModel(fund: fund), ownsModel: true))
    }

    private var model: TradeModel { observed.model as! TradeModel }

    var body: some View {
        let s = observed.state
        Form {
            Section {
                WalletField(label: "\(tcopy.FROM) (\(s.from))", text: binding(s.fromAmount) { model.setAmount(text: $0) }, number: true, disabled: s.busy, id: "trade-from")
                Button(UiCopy.shared.balance(amount: "\(LocaleFormat.shared.toFixed(value: s.balance, decimals: 3)) \(s.from)")) { model.useBalance() }.font(.footnote).accessibilityIdentifier("trade-balance")
                Button { model.flip() } label: { Image(appIcon: .sort).tapTarget() }.accessibilityLabel(WalletUiCopy.shared.SWAP).frame(maxWidth: .infinity).disabled(s.busy).accessibilityIdentifier("trade-flip")
                Text("\(tcopy.TO) (\(bidiValue(s.to))): \(bidiValue(LocaleFormat.shared.toFixed(value: s.toAmount, decimals: 3)))").bold().accessibilityIdentifier("trade-to")
                if let rate = s.rateText { Text(rate).font(.footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("trade-rate") }
                Text("\(tr.FEE): \(tr.FREE)").font(.footnote).foregroundStyle(theme.secondary)
                if !s.errors.isEmpty { Text(s.errors.joined(separator: "\n")).font(.footnote).foregroundStyle(theme.dangerText).announces(s.errors.joined(separator: "\n"), debounce: true).accessibilityIdentifier("trade-errors") }
                if s.bookLoading { ProgressView() }
            }
            SendPhaseView(phase: s.phase) { dismiss() }
            if let result = s.result {
                Section {
                    Text(result).bold().accessibilityIdentifier("trade-result")
                    if s.pending { Text(tr.PENDING_BODY).font(.footnote) }
                    Button(tr.NEW_SWAP) { model.reset() }
                    Button(tr.DONE) { dismiss() }
                }
            } else if !s.busy {
                Button(tcopy.NEXT) { model.next() }.frame(maxWidth: .infinity).disabled(!s.continueEnabled).accessibilityIdentifier("trade-next")
            }
        }
        .navigationTitle(tr.TITLE).navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .modifier(CloseOn(closed: s.closed))
        .modifier(TradeAlertModifier(prompt: s.prompt, title: tr.CONFIRM_SWAP, onConfirm: { model.confirm() }, onDismiss: { model.dismissPrompt() }))
        .modifier(LeaveGuard(busy: s.busy))
        .accessibilityIdentifier("trade-screen")
    }
}

private struct TradeAlertModifier: ViewModifier {
    let prompt: TradePrompt?
    let title: String
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    func body(content: Content) -> some View {
        content.alert((prompt as? TradePromptAlert)?.title ?? title, isPresented: Binding(get: { prompt != nil }, set: { if !$0 { onDismiss() } })) {
            if prompt is TradePromptConfirm {
                Button(copy.CONFIRM, action: onConfirm).accessibilityIdentifier("prompt-confirm")
                Button(copy.CANCEL, role: .cancel, action: onDismiss)
            } else {
                Button(UiCopy.shared.OK, action: onDismiss)
            }
        } message: {
            if let c = prompt as? TradePromptConfirm { Text(c.text) }
            if let a = prompt as? TradePromptAlert { Text(a.message) }
        }
    }
}

/// RN redeemScreen (promote, Boost+, RC top-up).
struct RedeemView: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var observed: Observed<RedeemState>
    @Environment(\.appTheme) private var theme
    private let rc = RedeemCopy.shared

    init(screen: AppScreenRedeem, env: AppEnvironment) {
        let kind = RedeemKind.entries.first { $0.rn == screen.redeemType } ?? .promote
        _observed = StateObject(wrappedValue: Observed(env.redeemModel(kind: kind, permlink: screen.permlink), ownsModel: true))
    }

    private var model: RedeemModel { observed.model as! RedeemModel }

    var body: some View {
        let s = observed.state
        Form {
            Section {
                Text("\(rc.USER): \(bidiValue("@" + (s.user ?? "")))")
                Text(s.balance.map { rc.points(value: LocaleFormat.shared.toFixed(value: $0.doubleValue, decimals: 3)) } ?? "--").bold().foregroundStyle(theme.accentText).accessibilityIdentifier("redeem-balance")
                if s.kind == .rcTopup { Text(rc.RC_DESC).font(.footnote).foregroundStyle(theme.secondary) }
                if s.kind == .promote { WalletField(label: rc.PERMLINK_PLACEHOLDER, text: binding(s.permlink) { model.setPermlink(text: $0) }, disabled: s.busy, id: "redeem-permlink") }
                if let until = s.boostedUntil { Text(rc.alreadyBoosted(date: until)).font(.footnote).foregroundStyle(theme.dangerText).announces(rc.alreadyBoosted(date: until), debounce: true) }
            }
            Section {
                if s.pricesLoading { ProgressView() }
                Picker(rc.DAYS, selection: Binding(get: { s.days }, set: { model.setDays(days: $0) })) {
                    ForEach(s.prices, id: \.duration) { p in Text(Fmt.int(p.duration)).tag(p.duration) }
                }
                .pickerStyle(.segmented).accessibilityIdentifier("redeem-days")
                HStack { Text(L.Native.Ios.Wallet.days(count: Int(s.days))); Spacer(); Text(s.priceText).bold().accessibilityIdentifier("redeem-price") }
                if s.insufficient && s.kind == .rcTopup {
                    Text(rc.INSUFFICIENT).foregroundStyle(theme.dangerText)
                    Button(rc.BUY_POINTS) { app.push(AppScreenCompanion.shared.of(route: .boost, arg: s.user ?? "")) }
                }
            }
            SendPhaseView(phase: s.phase) {}
            if !s.busy { Button(tcopy.NEXT) { model.next() }.frame(maxWidth: .infinity).disabled(!s.enabled).accessibilityIdentifier("redeem-next") }
        }
        .navigationTitle(s.kind.title).navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .modifier(CloseOn(closed: s.closed))
        .modifier(TradeAlertModifier(prompt: s.prompt, title: s.kind.title, onConfirm: { model.confirm() }, onDismiss: { model.dismissPrompt() }))
        .modifier(LeaveGuard(busy: s.busy))
        .accessibilityIdentifier("redeem-screen")
    }
}

/// RN AssetsSelect.
struct ManageAssetsView: View {
    @StateObject private var observed: Observed<ManageAssetsState>
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.manageAssetsModel(), ownsModel: true))
    }

    private var model: ManageAssetsModel { observed.model as! ManageAssetsModel }

    var body: some View {
        let s = observed.state
        List {
            Section(copy.SELECTED_ASSETS) {
                if s.selected.isEmpty { Text(copy.NO_SELECTED_ASSETS).font(.footnote).foregroundStyle(theme.secondary) }
                ForEach(s.selected, id: \.symbol) { a in
                    Button { model.toggle(symbol: a.symbol) } label: {
                        HStack { Image(appIcon: .checkbox, filled: true).accessibilityHidden(true); AvatarView(url: a.iconUrl ?? "", size: 24); Text(a.symbol) }
                    }
                    .accessibilityAddTraits(.isSelected)
                    .accessibilityIdentifier("selected-\(a.symbol)")
                }
                .onMove { from, to in if let f = from.first { model.move(from: Int32(f), to: Int32(to > f ? to - 1 : to)) } }
            }
            Section(copy.AVAILABLE_ASSETS) {
                ForEach(s.visibleAvailable, id: \.symbol) { a in
                    Button { model.toggle(symbol: a.symbol) } label: {
                        HStack { Image(appIcon: .checkbox).accessibilityHidden(true); AvatarView(url: a.iconUrl ?? "", size: 24); Text(a.symbol) }
                    }
                    .accessibilityIdentifier("available-\(a.symbol)")
                }
            }
        }
        .searchable(text: Binding(get: { s.query }, set: { model.setQuery(q: $0) }), prompt: copy.SEARCH)
        .overlay { if s.isLoading { ProgressView() } }
        .navigationTitle(copy.MANAGE_ASSETS).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            EditButton()
            if s.saving { ProgressView() } else { Button(copy.CONFIRM) { model.confirm() }.disabled(s.user == nil || s.isLoading).accessibilityIdentifier("assets-confirm") }
        }
        .onAppear { model.start() }
        .modifier(CloseOn(closed: s.closed))
        .alert(s.error ?? "", isPresented: Binding(get: { s.error != nil }, set: { if !$0 { model.dismissError() } })) { Button(UiCopy.shared.OK) { model.dismissError() } }
        .accessibilityIdentifier("manage-assets")
    }
}

/// RN tippingDialog.
struct TipSheet: View {
    @StateObject private var observed: Observed<TipState>
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    private let tc = TipCopy.shared

    init(author: String, permlink: String, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.tipModel(author: author, permlink: permlink), ownsModel: true))
    }

    private var model: TipModel { observed.model as! TipModel }

    var body: some View {
        let s = observed.state
        NavigationStack {
            Form {
                Section {
                    Text(tc.supportAuthor(author: s.author)).foregroundStyle(theme.secondary)
                    Text("\(tc.EXISTING) (\(Fmt.int(s.tipsCount)))").font(.caption).foregroundStyle(theme.secondary)
                    ForEach(s.tipsTotals.indices, id: \.self) { i in
                        let pair = s.tipsTotals[i]
                        Text(bidiValue("\(pair.second ?? "") \(pair.first ?? "")")).font(.caption)
                    }
                }
                Section(tc.TOKEN) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(s.currencies, id: \.symbol) { c in
                                Button { model.setCurrency(symbol: c.symbol) } label: {
                                    VStack {
                                        Text(c.symbol).bold()
                                        if !c.balance.isEmpty { Text(c.balance).font(.caption2) }
                                    }
                                    .padding(8)
                                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(c.symbol == s.currency ? theme.accent : theme.border))
                                }
                                .buttonStyle(.plain).accessibilityElement(children: .combine)
                                .accessibilityAddTraits(c.symbol == s.currency ? [.isButton, .isSelected] : .isButton)
                                .accessibilityIdentifier("tip-currency-\(c.symbol)")
                            }
                        }
                    }
                }
                Section(footer: Text(tc.maxDecimals(n: s.selected.precision))) {
                    WalletField(label: tc.AMOUNT, text: binding(s.amount) { model.setAmount(text: $0) }, number: true, disabled: s.busy, id: "tip-amount")
                }
                if s.user == nil { Text(tc.LOGIN_REQUIRED).foregroundStyle(theme.dangerText) }
                SendPhaseView(phase: s.phase) { dismiss() }
                if !s.busy { Button(tc.SEND) { model.send() }.frame(maxWidth: .infinity).disabled(!s.sendEnabled).accessibilityIdentifier("tip-send") }
            }
            .navigationTitle(tc.TITLE).navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled(s.busy)
        .onAppear { model.start() }
        .modifier(CloseOn(closed: s.closed))
        .alert(tc.CONFIRM_TITLE, isPresented: Binding(get: { s.confirming }, set: { if !$0 { model.dismissConfirm() } })) {
            Button(copy.CONFIRM) { model.confirm() }.accessibilityIdentifier("tip-confirm")
            Button(copy.CANCEL, role: .cancel) { model.dismissConfirm() }
        } message: {
            Text(tc.confirm(amount: s.amount, currency: s.currency, author: s.author))
        }
        .accessibilityIdentifier("tip-sheet")
    }
}

/// RN TRANSFER_FAVORITES: pick the recipient from the favourite accounts.
struct FavoritesSheet: View {
    let onPick: (String) -> Void
    @StateObject private var observed: Observed<TransferFavoritesState>

    init(env: AppEnvironment, onPick: @escaping (String) -> Void) {
        self.onPick = onPick
        _observed = StateObject(wrappedValue: Observed(env.transferFavoritesModel(), ownsModel: true))
    }

    private var model: TransferFavoritesModel { observed.model as! TransferFavoritesModel }

    var body: some View {
        let s = observed.state
        NavigationStack {
            List(s.visible, id: \.self) { name in Button("@\(name)") { onPick(name) }.accessibilityIdentifier("favorite-\(name)") }
                .overlay { if s.isLoading { ProgressView() } }
                .searchable(text: Binding(get: { s.query }, set: { model.setQuery(q: $0) }), prompt: tcopy.TO_PLACEHOLDER)
                .navigationTitle(copy.PICK_FAVORITE).navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { model.start() }
        .presentationDetents([.medium, .large])
    }
}

/// Back while a send runs: asks first (the send goes on without the screen and reports its outcome as a toast).
private struct LeaveGuard: ViewModifier {
    let busy: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var asking = false

    func body(content: Content) -> some View {
        content
            .blocksBack(busy)
            .toolbar {
                if busy {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { asking = true } label: { Image(appIcon: .back) }.accessibilityLabel(UiCopy.shared.BACK).accessibilityIdentifier("leave-guard-back")
                    }
                }
            }
            .alert(copy.LEAVE_TITLE, isPresented: $asking) {
                Button(copy.LEAVE) { dismiss() }.accessibilityIdentifier("leave-confirm")
                Button(copy.STAY, role: .cancel) {}
            } message: { Text(copy.LEAVE_MESSAGE) }
    }
}

/// RN QR_SCAN with `onScan`: the scanner for the transfer's recipient; the code's text goes back to the form.
private struct RecipientScanner: View {
    @StateObject private var observed: Observed<QrScanState>
    let onValue: (String) -> Void
    let onClose: () -> Void

    init(env: AppEnvironment, onValue: @escaping (String) -> Void, onClose: @escaping () -> Void) {
        _observed = StateObject(wrappedValue: Observed(env.qrScanModel(purpose: .value), ownsModel: true))
        self.onValue = onValue
        self.onClose = onClose
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            QrScannerView(observed: observed, onDenied: onClose).ignoresSafeArea()
            Button(action: onClose) {
                Image(appIcon: .close).scaledFont(18, weight: .semibold).foregroundStyle(.white)
                    .frame(width: 44, height: 44).background(Circle().fill(Color.black.opacity(0.4)))
            }
            .accessibilityLabel(UiCopy.shared.CLOSE)
            .accessibilityIdentifier("transfer-qr-close")
            .padding(12)
        }
        .background(Color.black)
        .onEscape(onClose)
        .accessibilityIdentifier("transfer-qr")
        .onChange(of: observed.state.outcome as? NSObject) { _, _ in
            if let value = observed.state.outcome as? QrOutcomeValue {
                (observed.model as! QrScanModel).consumeOutcome()
                onValue(value.text)
            }
        }
    }
}
