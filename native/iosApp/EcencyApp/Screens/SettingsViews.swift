import EcencyCore
import SwiftUI

/// RN settingsScreen: General (currency, language, server, image server, NSFW, appearance, show images and the app
/// lock rows), Push Notification, Support Ecency, then rate, feedback, email digests, Manage Authorities and Delete
/// Account. RN's cards are the form's sections, its dropdowns menu pickers and its toggles switches.
struct SettingsView: View {
    /// The shared settings (AppContainer.settings), observed here: a nested observable does not redraw the container's views.
    @ObservedObject var observed: Observed<SettingsState>
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @EnvironmentObject private var lock: Observed<AppLockState>
    @Environment(\.appTheme) private var theme

    private var copy: SettingsCopy { SettingsCopy.shared }
    private var options: SettingsOptions { SettingsOptions.shared }
    private var settings: SettingsModel { app.env.settings }

    var body: some View {
        let state = observed.state
        let loggedIn = session.state.isLoggedIn
        List {
            Section {
                picker(copy.CURRENCY, options.CURRENCIES, selected: state.currency, id: "settings-currency") { settings.setCurrency(code: $0) }
                picker(L.Settings.language, options.LANGUAGES, selected: state.language, id: "settings-language") { settings.setLanguage(code: $0) }
                // RN "Chat Privacy" (signed in), read and saved through the chat API
                if loggedIn { ChatPrivacyRow(env: app.env).id(session.state.username ?? "") }
                serverRow(state)
                picker(copy.IMAGE_SERVER, options.IMAGE_SERVERS, selected: state.imageServer, id: "settings-image-server") { settings.setImageServer(server: $0) }
                picker(copy.NSFW, NsfwMode.entries.map { SettingsOption(label: $0.label, value: $0.value) }, selected: state.nsfw.value, id: "settings-nsfw") { value in
                    if let mode = NsfwMode.entries.first(where: { $0.value == value }) { settings.setNsfw(mode: mode) }
                }
                picker(copy.APPEARANCE, ThemeMode.entries.map { SettingsOption(label: $0.title, value: $0.name) }, selected: state.theme.name, id: "settings-theme") { name in
                    settings.setTheme(mode: ThemeMode.companion.of(name: name))
                }
                ShowImagesToggle(notifications: app.notifications)
                if loggedIn {
                    // RN toggles latch back: they show the lock's state and start its PIN flow
                    lockButton(copy.PIN_CODE, on: lock.state.enabled, id: "menu-pin-lock") {
                        if lock.state.enabled { app.env.appLock.startDisable() } else { app.env.appLock.startEnable(biometricAvailable: Biometrics.isAvailable) }
                    }
                    if lock.state.enabled {
                        if Biometrics.isAvailable {
                            lockButton(copy.BIOMETRIC, on: lock.state.biometricEnabled, id: "menu-biometric") {
                                app.env.appLock.startBiometric(enabled: !lock.state.biometricEnabled)
                            }
                        }
                        actionRow(copy.RESET_PIN, copy.RESET, id: "menu-change-pin") { app.env.appLock.startChange() }
                    }
                }
            } header: {
                Text(copy.GENERAL).foregroundStyle(theme.accentText).accessibilityIdentifier("settings-section-general")
            }
            if loggedIn {
                PushSettingsSection(push: app.push)
                if let support = state.support, state.supportAccount == session.state.username {
                    supportSection(state, support)
                }
            }
            Section {
                actionRow(copy.RATE_APP, copy.RATE, id: "settings-rate") { app.openStoreListing() }
                actionRow(copy.SEND_FEEDBACK, copy.SEND, id: "settings-feedback") { app.sendFeedback() }
                if loggedIn {
                    actionRow(copy.EMAIL_DIGESTS, copy.MANAGE, id: "settings-email-digests") { app.push(AppScreenEmailDigests.shared) }
                    // RN settings: the account's keys and authorities, behind the PIN
                    actionRow(copy.MANAGE_AUTHORITIES, copy.OPEN, id: "menu-backup-keys") { app.push(AppScreenBackupKeys.shared) }
                    // RN settings "Delete Account": asks first (ShellPromptsViews.swift)
                    Button { app.env.deleteAccount.ask() } label: {
                        HStack {
                            Text(DeleteAccountCopy.shared.SETTINGS_ROW).bold().foregroundStyle(theme.secondary)
                            Spacer()
                            Image(appIcon: .discard).foregroundStyle(theme.secondary).accessibilityHidden(true)
                        }
                    }
                    .accessibilityIdentifier("menu-delete-account")
                }
            }
        }
        .overlay(alignment: .top) {
            // RN's RefreshControl spins while a server is checked
            if state.checkingServer { ProgressView().padding(8).accessibilityIdentifier("server-checking") }
        }
        .navigationTitle(copy.TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings")
        .onAppear { settings.onShow() }
        .onDisappear { settings.onHide() }
    }

    private func picker(_ title: String, _ items: [SettingsOption], selected: String, id: String, onSelect: @escaping (String) -> Void) -> some View {
        Picker(title, selection: Binding(get: { selected }, set: { if $0 != selected { onSelect($0) } })) {
            ForEach(items, id: \.value) { Text($0.label).tag($0.value) }
        }
        .settingsPickerStyle()
        .accessibilityIdentifier(id)
    }

    /// RN `_changeApi`: the groomed names; "Checking..." while a check runs.
    private func serverRow(_ state: SettingsState) -> some View {
        // the name goes under the label at accessibility sizes (side by side they overlapped the next row)
        AdaptiveStack(spacing: 4) {
            // one VoiceOver stop: the menu below reads "Server" and the name
            Text(copy.SERVER).accessibilityHidden(true)
            FormRowGap(minLength: 0)
            // the name is a plain Text that sizes the row; the menu covers it (a Menu's own label in a List row is
            // measured as one line, and a wrapped name was drawn over the next row)
            Text(state.serverLabel).foregroundStyle(theme.accentText).multilineTextAlignment(.leading)
                .accessibilityHidden(true)
                .overlay {
            Menu {
                ForEach(Array(state.servers.enumerated()), id: \.offset) { index, server in
                    // the saved server is the checked row (RN #2672 always highlighted rpc.ecency.com)
                    Button { if index != Int(state.serverIndex) { settings.selectServer(server: server) } } label: {
                        if index == Int(state.serverIndex) {
                            Label(options.serverLabel(server: server), appIcon: .check)
                        } else {
                            Text(options.serverLabel(server: server))
                        }
                    }
                    .accessibilityAddTraits(index == Int(state.serverIndex) ? .isSelected : [])
                }
            } label: {
                Color.clear.contentShape(Rectangle())
            }
            .disabled(state.checkingServer || state.servers.isEmpty)
            .accessibilityLabel(copy.SERVER)
            .accessibilityValue(state.serverLabel)
            .accessibilityIdentifier("settings-server")
                }
        }
    }

    private func lockButton(_ title: String, on: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            AdaptiveStack(spacing: 4) {
                Text(title).foregroundStyle(theme.text)
                FormRowGap(minLength: 0)
                Text(on ? L.Native.Ios.Settings.on : L.Native.Ios.Settings.off).foregroundStyle(on ? theme.accentText : theme.secondary)
            }
        }
        .accessibilityIdentifier(id)
        // read as a switch: the title, then on or off (the button starts the PIN flow)
        .accessibilityLabel(title)
        .accessibilityValue(on ? L.Native.Ios.Settings.on : L.Native.Ios.Settings.off)
        .accessibilityAddTraits(.isToggle)
    }

    private func actionRow(_ title: String, _ action: String, id: String, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            AdaptiveStack(spacing: 4) {
                Text(title).foregroundStyle(theme.text)
                FormRowGap(minLength: 0)
                Text(action).foregroundStyle(theme.accentText)
            }
        }
        .accessibilityIdentifier(id)
    }

    private func supportSection(_ state: SettingsState, _ support: SupportSettings) -> some View {
        let beneficiary = Int(state.beneficiaryPercent)
        let curation = Int(state.curationPercent)
        return Section {
            Toggle(copy.SUPPORT_BENEFICIARY, isOn: Binding(get: { beneficiary > 0 }, set: { settings.setSupportBeneficiary(on: $0) }))
                .accessibilityIdentifier("support-beneficiary")
            if beneficiary > 0 {
                percentPicker(copy.SUPPORT_BENEFICIARY_PERCENT, options.BENEFICIARY_PERCENTS, selected: beneficiary, id: "support-beneficiary-percent") {
                    settings.setSupportBeneficiaryPercent(percent: Int32($0))
                }
            }
            Toggle(copy.SUPPORT_CURATION, isOn: Binding(get: { curation > 0 }, set: { settings.setSupportCuration(on: $0) }))
                .accessibilityIdentifier("support-curation")
            if curation > 0 {
                percentPicker(copy.SUPPORT_CURATION_PERCENT, options.CURATION_PERCENTS, selected: curation, id: "support-curation-percent") {
                    settings.setSupportCurationPercent(percent: Int32($0))
                }
            }
        } header: {
            Text(copy.SUPPORT).foregroundStyle(theme.accentText)
        }
    }

    private func percentPicker(_ title: String, _ presets: [KotlinInt], selected: Int, id: String, onSelect: @escaping (Int) -> Void) -> some View {
        Picker(title, selection: Binding(get: { selected }, set: { if $0 != selected { onSelect($0) } })) {
            // RN shows the saved percent even when it is not a preset (the editor chip can save any)
            if !presets.contains(where: { $0.intValue == selected }) { Text(Fmt.percent(selected)).tag(selected) }
            ForEach(presets.map(\.intValue), id: \.self) { Text(Fmt.percent($0)).tag($0) }
        }
        .settingsPickerStyle()
        .accessibilityIdentifier(id)
    }
}

/// RN emailDigestsScreen.
struct EmailDigestsScreen: View {
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<EmailDigestsState>
    @State private var confirm: String?

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.emailDigestsModel(), ownsModel: true))
    }

    private var model: EmailDigestsModel { observed.model as! EmailDigestsModel }
    private var copy: EmailDigestsCopy { EmailDigestsCopy.shared }

    var body: some View {
        let state = observed.state
        let mine = state.account != nil && state.account == session.state.username
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !mine {
                    EmptyView()
                } else if state.loading {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 32).accessibilityIdentifier("digests-loading")
                } else if let error = state.error {
                    message(error, id: "digests-error")
                } else {
                    if state.subscriptions.isEmpty { message(copy.EMPTY, id: "digests-empty") }
                    ForEach(state.addresses, id: \.email) { group in
                        card {
                            AdaptiveStack {
                                Text(group.email).font(.subheadline.weight(.semibold)).lineLimit(1).truncationMode(.middle)
                                    .accessibilityAddTraits(.isHeader)
                                FormRowGap(minLength: 0)
                                Button(copy.STOP_ALL) { confirm = group.email }
                                    .font(.footnote).foregroundStyle(theme.dangerText)
                                    .disabled(state.stopping != nil)
                                    .accessibilityIdentifier("stop-all-\(group.email)")
                            }
                            .padding(.bottom, 8)
                            Divider()
                            ForEach(group.rows, id: \.id) { row in
                                digestRow(copy.rowLabel(s: row), copy.meta(s: row), copy.MANAGE, id: "digest-\(row.id)") {
                                    model.open(type: row.type, target: row.target)
                                }
                            }
                        }
                    }
                    if state.showsDiscover {
                        card {
                            Text(copy.DISCOVER).font(.subheadline.weight(.semibold)).accessibilityAddTraits(.isHeader)
                            if state.showsOwnOffer, let account = state.account {
                                digestRow(DigestCopy.shared.LIST_OWN, nil, DigestCopy.shared.SUBSCRIBE, id: "digest-offer-own") { model.open(type: "own", target: account) }
                            }
                            if state.showsSiteOffer {
                                digestRow(DigestCopy.shared.LIST_SITE, nil, DigestCopy.shared.SUBSCRIBE, id: "digest-offer-site") {
                                    model.open(type: "site", target: EmailDigestsState.companion.SITE_TARGET)
                                }
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .background(theme.background)
        .navigationTitle(copy.TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("email-digests")
        .onAppear { model.start() }
        .alert(copy.STOP_ALL_TITLE, isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), presenting: confirm) { email in
            Button(DigestCopy.shared.CANCEL, role: .cancel) {}
            Button(copy.STOP_ALL, role: .destructive) { model.stopAll(email: email) }.accessibilityIdentifier("stop-all-confirm")
        } message: { email in
            Text(copy.stopAllBody(email: email))
        }
        .alert(state.alert ?? "", isPresented: Binding(get: { state.alert != nil }, set: { if !$0 { model.clearAlert() } })) {
            Button(DigestCopy.shared.OK, role: .cancel) { model.clearAlert() }
        }
    }

    private func message(_ text: String, id: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(.horizontal, 24).padding(.top, 24).accessibilityIdentifier(id)
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 0) { content() }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(theme.secondary.opacity(0.12)))
    }

    private func digestRow(_ title: String, _ meta: String?, _ action: String, id: String, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            AdaptiveStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body).foregroundStyle(theme.text)
                    if let meta { Text(meta).font(.footnote).foregroundStyle(theme.secondary) }
                }
                FormRowGap(minLength: 0)
                Text(action).font(.subheadline).foregroundStyle(theme.accentText)
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}

/// The gap between a row's label and its value inside an `AdaptiveStack`: a Spacer side by side, nothing once the
/// row stacks (a Spacer in the stacked VStack took height and the List row was drawn over the next one).
struct FormRowGap: View {
    var minLength: CGFloat = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        if typeSize < .stacksRows { Spacer(minLength: minLength) }
    }
}

/// Menu pickers at the usual sizes; at accessibility sizes a pushed list instead: a menu picker's row is measured
/// with its value on one line, and a wrapped value ("Ecency Images (Default)") was drawn over the row above.
private struct SettingsPickerStyle: ViewModifier {
    @Environment(\.dynamicTypeSize) private var typeSize

    func body(content: Content) -> some View {
        if typeSize.isAccessibilitySize { content.pickerStyle(.navigationLink) } else { content.pickerStyle(.menu) }
    }
}

extension View {
    fileprivate func settingsPickerStyle() -> some View { modifier(SettingsPickerStyle()) }
}
