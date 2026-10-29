import SwiftUI

/// Screen colors from the RN app's EStyleSheet tokens (`src/themes/lightTheme.ts`, `darkTheme.ts`), with the colors
/// that missed WCAG AA taken from `ContrastPalette` (README "Accessibility", iOS; the same values as Android). Dark:
/// accent and danger are fills under white text, and text in those colors uses `accentText` and `dangerText`.
/// Increase Contrast picks `highContrast`: darker grays, blue and red in light, lighter in dark.
struct AppTheme {
    /// `$primaryBackgroundColor`
    var background: Color
    /// `$primaryLightBackground`: image placeholders, separators between cards.
    var lightBackground: Color
    /// `$primaryBlack`: titles, names.
    var text: Color
    /// `$primaryDarkGray`: summaries, dates, payout.
    var secondary: Color
    /// `$iconColor`
    var icon: Color
    /// `$primaryBlue`: fills, tints and icons.
    var accent: Color
    /// `$primaryRed`: fills and icons.
    var danger: Color
    /// `$borderColor`
    var border: Color
    /// Text drawn in the accent color (links, text buttons) on `background`: AA in both themes.
    var accentText: Color
    /// Text drawn in the danger color (errors, warnings) on `background`: AA in both themes.
    var dangerText: Color
    var isDark = false
    /// Text in the success green (gains, scheduled and done states) on `background`: AA in both themes.
    var successText = Color(hex: ContrastPalette.lightSuccessText)

    static let light = AppTheme(
        background: Color(hex: 0xFFFFFF), lightBackground: Color(hex: 0xF6F6F6), text: Color(hex: 0x3C4449),
        secondary: Color(hex: ContrastPalette.lightSecondary), icon: Color(hex: ContrastPalette.lightIcon),
        accent: Color(hex: ContrastPalette.lightAccent), danger: Color(hex: ContrastPalette.lightDanger), border: Color(hex: 0xC5C5C5),
        accentText: Color(hex: ContrastPalette.lightAccent), dangerText: Color(hex: ContrastPalette.lightDanger)
    )

    static let dark = AppTheme(
        background: Color(hex: 0x1E2835), lightBackground: Color(hex: 0x2E3D51), text: Color(hex: 0xFCFCFC),
        secondary: Color(hex: 0xC1C5C7), icon: Color(hex: 0x788187), accent: Color(hex: ContrastPalette.darkAccentFill),
        danger: Color(hex: ContrastPalette.darkDangerFill), border: Color(hex: 0x526D91),
        accentText: Color(hex: ContrastPalette.darkAccentText), dangerText: Color(hex: ContrastPalette.darkDangerText), isDark: true,
        successText: Color(hex: 0x4FD688)
    )

    /// Increase Contrast: text colors reach about 7:1 in light; in dark AA on `lightBackground` too; icons 3:1.
    var highContrast: AppTheme {
        var t = self
        if isDark {
            t.secondary = Color(hex: 0xCCCFD1)
            t.icon = Color(hex: 0x9AA1A6)
            t.accentText = Color(hex: 0xA6C8FA)
            t.dangerText = Color(hex: 0xFFB0B0)
            t.successText = Color(hex: 0x86E5B0)
            t.border = Color(hex: 0x7A93B5)
        } else {
            t.secondary = Color(hex: 0x4E5458)
            t.icon = Color(hex: 0x919495)
            t.accent = Color(hex: 0x235298)
            t.accentText = Color(hex: 0x235298)
            t.danger = Color(hex: 0x9F2525)
            t.dangerText = Color(hex: 0x9F2525)
            t.successText = Color(hex: 0x086346)
            t.border = Color(hex: 0x8A8A8A)
        }
        return t
    }

    /// The app's tint: it colors text (plain buttons, links, navigation and menu items), so it is the AA text blue.
    var rootTint: Color { accentText }

    static func of(_ scheme: ColorScheme, contrast: ColorSchemeContrast = .standard) -> AppTheme {
        let base: AppTheme = scheme == .dark ? .dark : .light
        return contrast == .increased ? base.highContrast : base
    }
}

private struct AppThemeKey: EnvironmentKey {
    static let defaultValue = AppTheme.light
}

extension EnvironmentValues {
    var appTheme: AppTheme {
        get { self[AppThemeKey.self] }
        set { self[AppThemeKey.self] = newValue }
    }
}

/// Puts the app and post themes for the current color scheme into the environment.
struct ThemedRoot<Content: View>: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.colorSchemeContrast) private var contrast
    @ViewBuilder let content: Content

    var body: some View {
        content
            .environment(\.appTheme, AppTheme.of(scheme, contrast: contrast))
            .environment(\.postTheme, scheme == .dark ? .dark : .light)
            // the root tint colors text: plain text buttons, links, navigation and menu items. Filled buttons
            // (`.prominentFill`) take `accent` as their fill instead
            .tint(AppTheme.of(scheme, contrast: contrast).rootTint)
            // the motion level (reduce motion; Motion.swift)
            .motionRoot()
            // VoiceOver and Switch Control (Accessibility.swift)
            .modifier(AssistiveTechnologyRoot())
    }
}

/// `.borderedProminent` with the theme's fill color: white text on `accent` (5.04:1). The root tint is the text color
/// (`accentText`), too light under white text in dark mode.
struct ProminentFillStyle: PrimitiveButtonStyle {
    @Environment(\.appTheme) private var theme

    func makeBody(configuration: Configuration) -> some View {
        Button(configuration).buttonStyle(.borderedProminent).tint(theme.accent)
    }
}

extension PrimitiveButtonStyle where Self == ProminentFillStyle {
    static var prominentFill: ProminentFillStyle { ProminentFillStyle() }
}

/// The secondary button (was `.bordered`): text in an AA text color (the text blue unless given another, such as
/// `dangerText` for a downvote) on `lightBackground`. `.bordered` laid a 15% blue over white under blue text, 4.1:1.
struct SubtleFillStyle: ButtonStyle {
    var foreground: KeyPath<AppTheme, Color> = \.accentText
    @Environment(\.appTheme) private var theme
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(isEnabled ? theme[keyPath: foreground] : theme.secondary)
            .padding(.horizontal, 14).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(theme.lightBackground))
            .opacity(configuration.isPressed ? 0.6 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

extension ButtonStyle where Self == SubtleFillStyle {
    static var subtleFill: SubtleFillStyle { SubtleFillStyle() }
    static func subtleFill(_ foreground: KeyPath<AppTheme, Color>) -> SubtleFillStyle { SubtleFillStyle(foreground: foreground) }
}

/// RN point sizes that still follow Dynamic Type: the size scales with the text style it is relative to.
struct ScaledFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    private let weight: Font.Weight

    init(size: CGFloat, weight: Font.Weight, relativeTo style: Font.TextStyle) {
        _size = ScaledMetric(wrappedValue: size, relativeTo: style)
        self.weight = weight
    }

    func body(content: Content) -> some View {
        content.font(.system(size: size, weight: weight))
    }
}

extension View {
    func scaledFont(_ size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> some View {
        modifier(ScaledFont(size: size, weight: weight, relativeTo: style))
    }
}
