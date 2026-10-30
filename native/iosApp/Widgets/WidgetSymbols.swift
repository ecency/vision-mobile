import SwiftUI

/// The widgets' icons. The widget extension does not link the shared core, so it cannot use AppIcon; these are the
/// same SF Symbols native/scripts/icons/icons.tsv lists for those meanings (the check in AppIconSourceTest keeps
/// them in the list). Widgets show them filled: they are status marks on the home screen.
enum WidgetSymbol: String {
    /// AppIcon.NOTIFICATIONS, filled.
    case notifications = "bell.fill"
    /// AppIcon.BOOST, filled (the quest streak).
    case streak = "flame.fill"
    /// AppIcon.COMPOSE.
    case compose = "square.and.pencil"
    /// AppIcon.WAVES (the system stand-in for the Waves mark).
    case waves = "water.waves"
}

extension Image {
    init(widgetSymbol: WidgetSymbol) { self.init(systemName: widgetSymbol.rawValue) }
}

extension Label where Title == Text, Icon == Image {
    init(_ title: String, widgetSymbol: WidgetSymbol) { self.init(title, systemImage: widgetSymbol.rawValue) }
}
