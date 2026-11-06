import EcencyCore
import SwiftUI

/// Issue #3656: the one question iOS asks before its first automatic icon switch (README "App icons"), once a
/// celebration is due, never while locked or before the welcome screen is done.
struct LauncherIconQuestionHost: View {
    @EnvironmentObject private var app: AppContainer
    @ObservedObject var icons: Observed<LauncherIconState>
    @ObservedObject var lock: Observed<AppLockState>

    var body: some View {
        Color.clear
            .alert(L.Native.Ios.AppIcon.consentTitle,
                   isPresented: Binding(get: { icons.state.askConsent && app.termsAccepted && !lock.state.hidesContent }, set: { _ in })) {
                Button(L.Native.Ios.AppIcon.consentNo, role: .cancel) { app.launcherIconSwitcher.answerConsent(false) }
                    .accessibilityIdentifier("launcher-icon-no")
                Button(L.Native.Ios.AppIcon.consentYes) { app.launcherIconSwitcher.answerConsent(true) }
                    .accessibilityIdentifier("launcher-icon-yes")
            } message: {
                Text(L.Native.Ios.AppIcon.consentMessage)
            }
    }
}

/// Settings > App icon: seasonal icons on or off, and every icon to pick by hand (the everyday one included).
struct LauncherIconSection: View {
    @EnvironmentObject private var app: AppContainer
    @ObservedObject var icons: Observed<LauncherIconState>
    @Environment(\.appTheme) private var theme

    var body: some View {
        let state = icons.state
        Section {
            Toggle(LauncherIconCopy.shared.SEASONAL, isOn: Binding(get: { state.seasonal }, set: { app.launcherIconSwitcher.setSeasonal($0) }))
                .accessibilityIdentifier("settings-seasonal-icons")
            Picker(LauncherIconCopy.shared.TITLE, selection: Binding(get: { state.current }, set: { if $0 != state.current { app.launcherIconSwitcher.pick($0) } })) {
                ForEach(LauncherIcon.entries, id: \.self) { icon in
                    Label {
                        Text(icon.title)
                    } icon: {
                        Image("IconPreview-\(icon.key)").resizable().frame(width: 30, height: 30)
                            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                            .accessibilityHidden(true)
                    }
                    .tag(icon)
                    .accessibilityIdentifier("app-icon-\(icon.key)")
                }
            }
            // the other Settings pickers' style: a menu (its value in the accent colour), a list at accessibility sizes
            .settingsPickerStyle()
            .accessibilityIdentifier("settings-app-icon")
        } header: {
            Text(LauncherIconCopy.shared.TITLE).foregroundStyle(theme.accentText)
        } footer: {
            Text(LauncherIconCopy.shared.SEASONAL_HINT).foregroundStyle(theme.secondary)
        }
    }
}
