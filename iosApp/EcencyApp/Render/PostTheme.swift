import SwiftUI

/// Post body colors and metrics, matching the RN app's EStyleSheet theme tokens (and PostTheme.kt on Android).
struct PostTheme {
    var text: Color
    var link: Color
    var danger: Color
    /// `$darkIconColor`: blockquote border, table header, inline code.
    var muted: Color
    /// `$iconColor`: chip outline, hidden image icon and label.
    var chipBorder: Color
    var tableRow: Color
    var tableBorder: Color
    var placeholder: Color
    /// `$primaryBackgroundColor`, behind the whole post.
    var background: Color
    /// `$borderTopColor`, the dashed outline of a hidden image.
    var hiddenBorder: Color
    var isDark = false
    var bodySize: CGFloat = 16
    var bodyBold = false
    var headingSizes: [CGFloat] = [24, 20, 18, 16, 15, 14]
    /// Heading top and bottom margins in em, react-native-render-html's user agent defaults.
    var headingMarginsEm: [CGFloat] = [0.67, 0.83, 1, 1.33, 1.67, 2.33]
    var paragraphSpacing: CGFloat = 6
    var listItemSpacing: CGFloat = 12
    var imageRadius: CGFloat = 8

    static let light = PostTheme(
        // link and danger are text colors: the AA text tokens of AppTheme (accentText, dangerText), not RN's fills
        text: Color(hex: 0x3C4449), link: Color(hex: ContrastPalette.lightAccent), danger: Color(hex: ContrastPalette.lightDanger),
        muted: Color(hex: 0xC1C5C7), chipBorder: Color(hex: 0xC1C5C7), tableRow: Color(hex: 0xF5F5F5),
        tableBorder: Color(hex: 0xFFFFFF), placeholder: Color(hex: 0xF6F6F6),
        background: Color(hex: 0xFFFFFF), hiddenBorder: Color(hex: 0xCFCFCF)
    )

    static let dark = PostTheme(
        text: Color(hex: 0xFCFCFC), link: Color(hex: ContrastPalette.darkAccentText), danger: Color(hex: ContrastPalette.darkDangerText),
        muted: Color(hex: 0x526D91), chipBorder: Color(hex: 0x788187), tableRow: Color(hex: 0x2E3D51),
        tableBorder: Color(hex: 0x1E2835), placeholder: Color(hex: 0x2E3D51),
        background: Color(hex: 0x1E2835), hiddenBorder: Color(hex: 0x757575), isDark: true
    )
}

private struct PostThemeKey: EnvironmentKey {
    static let defaultValue = PostTheme.light
}

extension EnvironmentValues {
    var postTheme: PostTheme {
        get { self[PostThemeKey.self] }
        set { self[PostThemeKey.self] = newValue }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
