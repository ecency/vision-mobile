import EcencyCore
import SwiftUI

// Two panes on a wide window (README "iPhone Duo", #3601): on every tab the tab's list stays on
// the leading side and what it opens shows beside it, as in Mail. Only when the window has room
// (NavState.hasRoomForPanes: iPhone Duo's inner display held sideways); a phone never shows them.

private struct InPaneKey: EnvironmentKey {
    static let defaultValue = false
}

private struct PaneTopKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    /// True for a screen shown in the pane: it shares the app's bar with the list, so it never hides it.
    var isInPane: Bool {
        get { self[InPaneKey.self] }
        set { self[InPaneKey.self] = newValue }
    }

    /// False for a pane screen kept under the one on top (its scroll, its playing audio): it puts nothing in the
    /// pane's header.
    var isPaneTop: Bool {
        get { self[PaneTopKey.self] }
        set { self[PaneTopKey.self] = newValue }
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
    /// the app's bar belongs to the list's tabs. Not [shown], there is none (and the pane's header shows the title).
    func screenBarItem<Item: View>(_ placement: ScreenBarPlacement, shown: Bool = true, @ViewBuilder _ item: () -> Item) -> some View {
        modifier(ScreenBarItem(placement: placement, shown: shown, item: item()))
    }
}

private struct ScreenBarItem<Item: View>: ViewModifier {
    let placement: ScreenBarPlacement
    let shown: Bool
    let item: Item
    @Environment(\.isInPane) private var isInPane
    @Environment(\.isPaneTop) private var isPaneTop

    func body(content: Content) -> some View {
        if !shown || (isInPane && !isPaneTop) {
            content
        } else if isInPane {
            // added to what the screen's own views set (a screen with a title item and actions has both)
            content.transformPreference(PaneBarKey.self) { $0[placement] = AnyView(item) }
        } else if placement == .principal {
            content.toolbar { ToolbarItem(placement: .principal) { item } }
        } else {
            content.toolbar { ToolbarItemGroup(placement: .topBarTrailing) { item } }
        }
    }
}

/// A pane screen's title for the pane's header (the navigation title is the app bar's, which the list owns there).
private struct PaneTitleKey: PreferenceKey {
    static let defaultValue: String? = nil
    static func reduce(value: inout String?, nextValue: () -> String?) { value = nextValue() ?? value }
}

extension View {
    /// A screen's title: the navigation title on a phone, and also the pane header's title beside a list.
    func screenTitle(_ title: String) -> some View {
        navigationTitle(title).modifier(PaneTitle(title: title))
    }
}

private struct PaneTitle: ViewModifier {
    let title: String
    @Environment(\.isInPane) private var isInPane
    @Environment(\.isPaneTop) private var isPaneTop

    func body(content: Content) -> some View {
        if isInPane && isPaneTop { content.preference(key: PaneTitleKey.self, value: title) } else { content }
    }
}

extension View {
    /// List rows in the pane: a list away from the window's edge gets no default row margins, so its rows would sit
    /// against the divider; there they get the phone's. Elsewhere nothing changes.
    func paneRowInsets() -> some View { modifier(PaneRowInsets()) }
}

private struct PaneRowInsets: ViewModifier {
    @Environment(\.isInPane) private var isInPane

    func body(content: Content) -> some View {
        if isInPane { content.listRowInsets(EdgeInsets(top: 11, leading: 16, bottom: 11, trailing: 16)) } else { content }
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
        let width = Self.listWidth(window: viewport?.width ?? 0)
        // one list in both layouts, so it keeps its scroll when the menu gives its place to the pane
        HStack(spacing: 0) {
            if menu {
                // as wide as the drawer, which grows with the text size
                SideMenuDrawer(asColumn: true)
                    .transition(motion.pops ? .move(edge: .leading) : .opacity)
                separator
            }
            list().frame(minWidth: menu ? 0 : width, maxWidth: menu ? .infinity : width)
            if !menu {
                separator
                DetailPane(tab: tab)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .transition(motion.pops ? .move(edge: .trailing) : .opacity)
            }
        }
        .animation(motion.isOff ? nil : .easeOut(duration: 0.25), value: menu)
    }

    private var separator: some View { Rectangle().fill(theme.border).frame(width: 1 / 3).accessibilityHidden(true) }

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
    /// The pane screen's title (screenTitle).
    @State private var title: String?

    var body: some View {
        if let top = app.nav.pane.last {
            // every screen of the pane stays, the ones under the top hidden: back finds a post where it was left
            // (its scroll, its narration), as a navigation stack keeps it
            ZStack {
                ForEach(app.nav.pane, id: \.id) { item in
                    let isTop = item.id == top.id
                    DestinationView(screen: item.screen)
                        .environment(\.isInPane, true)
                        .environment(\.isPaneTop, isTop)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .opacity(isTop ? 1 : 0)
                        .allowsHitTesting(isTop)
                        .accessibilityHidden(!isTop)
                }
            }
                // room for the header, which shows the screen's own bar items (screenBarItem)
                .safeAreaInset(edge: .top, spacing: 0) { Color.clear.frame(height: headerHeight) }
                .overlayPreferenceValue(PaneBarKey.self, alignment: .top) { items in
                    header(items, title: title).onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                }
                .onPreferenceChange(PaneTitleKey.self) { title = $0 }
                .background(theme.background)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("detail-pane")
        } else {
            SwiftUI.Image(appIcon: tab.icon)
                .font(.largeTitle.weight(.light)).imageScale(.large)
                .foregroundStyle(theme.secondary.opacity(0.35))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(theme.background)
                .accessibilityHidden(true)
                .accessibilityIdentifier("detail-pane-empty")
        }
    }

    /// The user menu again (the pane keeps its screen behind it), back while the pane is deeper than its first
    /// screen, then the screen's title (or its own title item) in the middle and its actions at the trailing end.
    private func header(_ items: [ScreenBarPlacement: AnyView], title: String?) -> some View {
        HStack(spacing: 4) {
            Button { app.openDrawer() } label: { SwiftUI.Image(appIcon: .account).scaledFont(18).foregroundStyle(theme.icon).tapTarget() }
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
            // the screen's own title item (a profile's compact header), else its title, as the navigation bar does
            if let principal = items[.principal] {
                principal
            } else if let title, !title.isEmpty {
                Text(title).scaledFont(16, weight: .semibold).foregroundStyle(theme.text).lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("pane-title")
            }
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
