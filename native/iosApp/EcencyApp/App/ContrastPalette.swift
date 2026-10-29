/// Every color changed from RN's tokens for WCAG AA contrast (README "Accessibility"), in one place so the change is
/// easy to review or revert: the same values as Android's theme. RN's original is on each line. Plain RGB values, so
/// the widget extension (which does not link the app) reads the same file. The values shared with Android are
/// approved by the owner (2026-10-27); the iOS only ones (`lightRcText`, `lightUnreadRow`) follow the same AA rule.
enum ContrastPalette {
    // light theme
    /// `$primaryDarkGray` text (RN 0x788187, 3.97:1 on white): 4.9:1.
    static let lightSecondary: UInt32 = 0x67717A
    /// `$primaryBlue` (RN 0x357CE6, 4.06:1): text, links and fills under white text.
    static let lightAccent: UInt32 = 0x2E6BD4
    /// `$primaryRed` (RN 0xE63535, 4.26:1): text and fills under white text.
    static let lightDanger: UInt32 = 0xD32F2F
    /// `$iconColor` (RN 0xC1C5C7, 1.7:1): icons reach 3:1; text in the icon color uses `lightSecondary`.
    static let lightIcon: UInt32 = 0x868F95

    // dark theme
    /// Blue fills under white text (RN 0x357CE6, 4.06:1 with white).
    static let darkAccentFill: UInt32 = 0x2E6BD4
    /// Red fills under white text (RN 0xE63535).
    static let darkDangerFill: UInt32 = 0xD32F2F
    /// Blue as text, link or icon tint on the dark background (RN 0x357CE6, 3.67:1).
    static let darkAccentText: UInt32 = 0x7FB0F7
    /// Red as text on the dark background (RN 0xE63535, 3.49:1).
    static let darkDangerText: UInt32 = 0xFF8F8F

    /// Resource credits text (RN 0x11C28B, 2.3:1 on white): 5.1:1. iOS only, by the approved AA rule (the Android
    /// profile draws no RC text);
    /// dark keeps RN's green (6.5:1 on the dark background).
    static let lightRcText: UInt32 = 0x0B7D59

    /// Success green as text (RN 0x4FD688, 1.9:1 on white): the RC green. Dark keeps RN's (8:1).
    static let lightSuccessText: UInt32 = lightRcText

    /// Unread notification rows (RN 0xEAF2FC: gray text on it 4.4:1): 4.6:1.
    static let lightUnreadRow: UInt32 = 0xF3F7FD

    /// Gray message pills (toast, banners) under white text (RN 0x788187); dark keeps RN's 0x526D91.
    static let lightMessageGray: UInt32 = lightSecondary
}
