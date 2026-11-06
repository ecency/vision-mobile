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
            // a row of our own: the system picker row drew its title in a grey that read under 4.5:1 beside the
            // selected icon's preview; here the title takes the theme's text colour and the value is the icon's name
            NavigationLink {
                LauncherIconList(icons: icons)
            } label: {
                // the name goes under the title at accessibility sizes, as the other Settings rows do
                AdaptiveStack(spacing: 4) {
                    Text(LauncherIconCopy.shared.TITLE).foregroundStyle(theme.text)
                    FormRowGap(minLength: 0)
                    Text(state.current.title).foregroundStyle(theme.accentText)
                }
            }
            .accessibilityIdentifier("settings-app-icon")
        } header: {
            Text(LauncherIconCopy.shared.TITLE).foregroundStyle(theme.accentText)
        } footer: {
            Text(LauncherIconCopy.shared.SEASONAL_HINT).foregroundStyle(theme.secondary)
        }
    }
}

/// Every icon with its preview; a tap picks it (the everyday one included) and the current one carries a checkmark.
struct LauncherIconList: View {
    @EnvironmentObject private var app: AppContainer
    @ObservedObject var icons: Observed<LauncherIconState>
    @Environment(\.appTheme) private var theme

    var body: some View {
        let current = icons.state.current
        ThemedForm {
            Section {
                ForEach(LauncherIcon.entries, id: \.self) { icon in
                    // the current one too: a pick keeps that icon and turns seasonal icons off, as on Android
                    Button { app.launcherIconSwitcher.pick(icon) } label: {
                        HStack(spacing: 12) {
                            Image("IconPreview-\(icon.key)").resizable().frame(width: 30, height: 30)
                                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                                .accessibilityHidden(true)
                            Text(icon.title).foregroundStyle(theme.text)
                            Spacer(minLength: 8)
                            if icon == current {
                                Image(appIcon: .check).foregroundStyle(theme.accentText).accessibilityHidden(true)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(icon == current ? .isSelected : [])
                    .accessibilityIdentifier("app-icon-\(icon.key)")
                }
            }
        }
        .navigationTitle(LauncherIconCopy.shared.TITLE)
    }
}
