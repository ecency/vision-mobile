import EcencyCore
import SwiftUI

// Two panes on a wide window (README "iPhone Duo", #3601): on every tab the tab's list stays on
// the leading side and what it opens shows beside it, as in Mail. Only when the window has room
// (NavState.hasRoomForPanes: iPhone Duo's inner display held sideways); a phone never shows them.

private struct InPaneKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// True for a screen shown in the pane: it shares the app's bar with the list, so it never hides it.
    var isInPane: Bool {
        get { self[InPaneKey.self] }
        set { self[InPaneKey.self] = newValue }
    }
}

/// Where a screen's bar item goes: its title's place, or the trailing actions.
enum ScreenBarPlacement: Hashable { case principal, trailing }

/// A screen's bar items for the pane's header, by placement (the last screen to set one wins).
private struct PaneBarKey: PreferenceKey {
    static let defaultValue: [ScreenBarPlacement: AnyView] = [:]
    static func reduce(value: inout [ScreenBarPlacement: AnyView], nextValue: () -> [ScreenBarPlacement: AnyView]) {
        value.merge(nextValue()) { $1 }
    }
}

extension View {
    /// A screen's bar item: in the navigation bar on a phone, in the pane's header beside a list (two panes), where
    /// the app's bar belongs to the list's tabs.
    func screenBarItem<Item: View>(_ placement: ScreenBarPlacement, @ViewBuilder _ item: () -> Item) -> some View {
        modifier(ScreenBarItem(placement: placement, item: item()))
    }
}

private struct ScreenBarItem<Item: View>: ViewModifier {
    let placement: ScreenBarPlacement
    let item: Item
    @Environment(\.isInPane) private var isInPane

    func body(content: Content) -> some View {
        if isInPane {
            content.preference(key: PaneBarKey.self, value: [placement: AnyView(item)])
        } else if placement == .principal {
            content.toolbar { ToolbarItem(placement: .principal) { item } }
        } else {
            content.toolbar { ToolbarItemGroup(placement: .topBarTrailing) { item } }
        }
    }
}

/// A tab's list with one column beside it (NavState.menuBesideList): the user menu before it while nothing is open,
/// then the list and the pane with what it opened, the menu closed. The account button or the pane's sidebar
/// button brings the menu back over the pane, which keeps its screen.
struct PanedTab<List: View>: View {
    let tab: AppTab
    @ViewBuilder let list: () -> List
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @Environment(\.viewportSize) private var viewport

    var body: some View {
        let menu = app.nav.menuBesideList(wide: true)
        HStack(spacing: 0) {
            if menu {
                SideMenuDrawer(asColumn: true)
                    .frame(width: Self.menuWidth)
                    .transition(motion.pops ? .move(edge: .leading) : .opacity)
                separator
                list().frame(maxWidth: .infinity)
            } else {
                list().frame(width: Self.listWidth(window: viewport?.width ?? 0))
                separator
                DetailPane(tab: tab)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .transition(motion.pops ? .move(edge: .trailing) : .opacity)
            }
        }
        .animation(motion.isOff ? nil : .easeOut(duration: 0.25), value: menu)
    }

    private var separator: some View { Rectangle().fill(theme.border).frame(width: 1 / 3).accessibilityHidden(true) }

    /// RN's drawer width.
    static var menuWidth: CGFloat { 280 }

    /// The list's width beside the pane: at least a 6.3-inch iPhone's column (402 pt), as RN laid its cards and
    /// filter bar out for one, a little wider on a larger window.
    static func listWidth(window: CGFloat) -> CGFloat { min(440, max(402, window * 0.42)) }
}

/// The pane: the top of what the list opened, with its own back (a post's author, its voters, then back to the
/// post). Not a stack of its own: SwiftUI gives a stack inside the app's stack no bar, so the pane's screen puts its
/// title and actions (a post's menu) in the app's bar, which iPhone Duo shows in its trailing column. Empty, it shows
/// the tab's icon, faint (only for a moment: an empty pane gives its place to the user menu).
private struct DetailPane: View {
    let tab: AppTab
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @State private var headerHeight: CGFloat = 44

    var body: some View {
        if let top = app.nav.pane.last {
            DestinationView(screen: top.screen)
                .environment(\.isInPane, true)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // a new screen in the pane is a new view, with its own model
                .id(top.id)
                // room for the header, which shows the screen's own bar items (screenBarItem)
                .safeAreaInset(edge: .top, spacing: 0) { Color.clear.frame(height: headerHeight) }
                .overlayPreferenceValue(PaneBarKey.self, alignment: .top) { items in
                    header(items).onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                }
                .background(theme.background)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("detail-pane")
        } else {
            SwiftUI.Image(appIcon: tab.icon)
                .font(.system(size: 64, weight: .light))
                .foregroundStyle(theme.secondary.opacity(0.35))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(theme.background)
                .accessibilityHidden(true)
                .accessibilityIdentifier("detail-pane-empty")
        }
    }

    /// The user menu again (the pane keeps its screen behind it), back while the pane is deeper than its first
    /// screen, then the screen's own bar items: its title's place in the middle, its actions at the trailing end.
    private func header(_ items: [ScreenBarPlacement: AnyView]) -> some View {
        HStack(spacing: 4) {
            Button { app.openDrawer() } label: { SwiftUI.Image(systemName: "sidebar.left").scaledFont(18).foregroundStyle(theme.icon).tapTarget() }
                .accessibilityLabel(menuLabel)
                .largeContent(menuLabel)
                .accessibilityIdentifier("pane-menu")
            if app.nav.pane.count > 1 {
                Button { app.panePath = Array(app.panePath.dropLast()) } label: {
                    SwiftUI.Image(appIcon: .back).scaledFont(18).foregroundStyle(theme.icon).tapTarget()
                }
                .accessibilityLabel(UiCopy.shared.BACK)
                .largeContent(UiCopy.shared.BACK, appIcon: .back)
                .accessibilityIdentifier("pane-back")
            }
            Spacer(minLength: 8)
            if let principal = items[.principal] { principal }
            Spacer(minLength: 8)
            if let trailing = items[.trailing] {
                HStack(spacing: 16) { trailing }.foregroundStyle(theme.accentText)
            }
        }
        .padding(.horizontal, 8)
        .background(theme.background)
    }

    /// The account button's own name: the same user menu.
    private var menuLabel: String {
        session.state.username.map { ShellUiCopy.shared.accountOf(username: $0) } ?? ShellUiCopy.shared.ACCOUNT_SIGNED_OUT
    }
}
