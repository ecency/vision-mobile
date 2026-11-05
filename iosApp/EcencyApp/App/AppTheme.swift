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
    /// The chat warning box (RN dmWarningContainer: the DM warning, the ban notice), RN's cream in light with a darker
    /// amber border than RN's (3:1 on the fill, RN's was 1.5:1); the same values as Android's `EcencyColors`.
    var warningBackground = Color(hex: 0xFFF6DF)
    var warningBorder = Color(hex: 0xB07800)
    /// Text and icons on `warningBackground`: AA in every theme.
    var warningText = Color(hex: 0x8A5B00)
    /// The danger box (the dApp phishing and request warnings): RN's pink in light, a dark red surface in dark, under
    /// `text` and a `dangerText` icon; the same values as Android's `EcencyColors`.
    var dangerBackground = Color(hex: 0xFDEDED)

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
        successText: Color(hex: 0x4FD688),
        // an amber dark surface: border 4.8:1, text 9.4:1 on it
        warningBackground: Color(hex: 0x3A3223), warningBorder: Color(hex: 0xC9963A), warningText: Color(hex: 0xFFD98A),
        // text 13.8:1 and dangerText 6.5:1 on it
        dangerBackground: Color(hex: 0x3D2427)
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
            t.warningBorder = Color(hex: 0xE8B04F)
            t.warningText = Color(hex: 0xFFE7B3)
        } else {
            t.secondary = Color(hex: 0x4E5458)
            t.icon = Color(hex: 0x919495)
            t.accent = Color(hex: 0x235298)
            t.accentText = Color(hex: 0x235298)
            t.danger = Color(hex: 0x9F2525)
            t.dangerText = Color(hex: 0x9F2525)
            t.successText = Color(hex: 0x086346)
            t.border = Color(hex: 0x8A8A8A)
            t.warningText = Color(hex: 0x6B4500)
        }
        return t
    }

    /// The app's tint: it colors text (plain buttons, links, navigation and menu items), so it is the AA text blue.
    var rootTint: Color { accentText }

    static func of(_ scheme: ColorScheme, contrast: ColorSchemeContrast = .standard) -> AppTheme {
        let base: AppTheme = scheme == .dark ? .dark : .light
        return contrast == .increased ? base.highContrast : base
    }

    /// A theme color for UIKit views (the privacy cover): resolved from the view's traits as `ThemedRoot` picks the
    /// theme from SwiftUI's color scheme and contrast.
    static func uiColor(_ color: KeyPath<AppTheme, Color>) -> UIColor {
        UIColor { traits in
            UIColor(of(traits.userInterfaceStyle == .dark ? .dark : .light,
                       contrast: traits.accessibilityContrast == .high ? .increased : .standard)[keyPath: color])
        }
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

/// A text button in the navigation bar drawn on the page itself, without the bar's glass capsule (iOS 26 and later).
/// In dark mode the capsule is a lighter grey (#475667) and the text blue on it measured 3.9:1, under AA; on the page
/// it is 6.5:1 (#3620). Icon buttons keep the capsule.
extension ToolbarContent {
    @ToolbarContentBuilder
    func onPage() -> some ToolbarContent {
        if #available(iOS 26, *) { sharedBackgroundVisibility(.hidden) } else { self }
    }
}

extension View {
    /// A horizontal scroll view right under the navigation bar: iOS 27 drew the bar's edge effect for it, a lighter
    /// band with a hairline over the status bar and the bar (#22303F on the dark theme's #1E2835), as if the page had
    /// scrolled under it (#3620). It scrolls sideways only, so nothing ever passes under the bar.
    @ViewBuilder
    func noBarEdgeEffect() -> some View {
        if #available(iOS 26, *) { scrollEdgeEffectHidden(true, for: .top) } else { self }
    }
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
