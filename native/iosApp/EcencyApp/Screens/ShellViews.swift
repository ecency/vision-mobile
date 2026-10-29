import EcencyCore
import Network
import SwiftUI

/// RN NetInfo: reachability from NWPathMonitor, delivered on the main thread.
final class NetworkMonitor {
    private let monitor = NWPathMonitor()

    func start(_ onChange: @escaping @MainActor (Bool) -> Void) {
        monitor.pathUpdateHandler = { path in
            let online = path.status == .satisfied
            DispatchQueue.main.async { MainActor.assumeIsolated { onChange(online) } }
        }
        monitor.start(queue: DispatchQueue(label: "com.ecency.network"))
    }

    deinit { monitor.cancel() }
}

/// RN tab tints (the same in both themes); the resting icons use the AA icon color (RN 0xC1C5C7 was 1.7:1 on white).
private let tabActive = Color(hex: 0x357CE6)
private let tabInactive = Color(hex: ContrastPalette.lightIcon)

extension AppTab {
    var identifier: String { "tab-\(name.lowercased())" }
}

/// RN's wavy dash, the Waves mark (AppIcon.waves): the one custom glyph, drawn from the shared WavesMark path that
/// Android draws too. A stroked path, so `.fill(tint)` paints it.
struct WavyDash: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let s = min(rect.width, rect.height) / CGFloat(WavesMark.shared.GRID)
        func pt(_ v: KotlinFloatArray, _ i: Int32) -> CGPoint {
            CGPoint(x: rect.minX + CGFloat(v.get(index: i)) * s, y: rect.minY + CGFloat(v.get(index: i + 1)) * s)
        }
        p.move(to: pt(WavesMark.shared.START, 0))
        for c in WavesMark.shared.CURVES { p.addCurve(to: pt(c, 4), control1: pt(c, 0), control2: pt(c, 2)) }
        return p.strokedPath(StrokeStyle(lineWidth: CGFloat(WavesMark.shared.STROKE) * s, lineCap: .round))
    }
}

/// RN BottomTabBar: five icons, no labels, 26 pt, 15 pt from the top, the home indicator inset (or 16) below.
struct BottomTabBar: View {
    let selected: AppTab
    let badges: Badges
    let onSelect: (AppTab) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.entries, id: \.self) { tab in
                TabButton(tab: tab, active: tab == selected, badge: badges.text(kind: tab.badge)) { onSelect(tab) }
            }
        }
        .background(Rectangle().fill(theme.background).shadow(color: .black.opacity(0.15), radius: 6).ignoresSafeArea(edges: .bottom))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("tab-bar")
    }
}

private struct TabButton: View {
    let tab: AppTab
    let active: Bool
    let badge: String?
    let onSelect: () -> Void
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @State private var selections = 0

    private var tint: Color { active ? tabActive : tabInactive }

    // the selected icon takes its colour with a short fade and bounces once (any icon pops; SF Symbols bounce)
    @ViewBuilder private var icon: some View {
        Group {
            if tab == .waves {
                WavyDash().fill(tint).frame(width: 26, height: 26).popOnChange(active, enabled: active)
            } else {
                Image(appIcon: tab.icon, filled: active).font(.system(size: 22)).foregroundStyle(tint).frame(width: 26, height: 26)
                    .contentTransition(motion.isOff ? .identity : .symbolEffect(.replace))
                    .symbolEffect(.bounce, options: .nonRepeating, value: selections)
            }
        }
        .animation(motion.fade(), value: active)
        .onChange(of: active) { _, now in if now && motion.pops { selections += 1 } }
    }

    var body: some View {
        Button { if !active { onSelect() } } label: {
            ZStack(alignment: .topTrailing) {
                icon
                if let badge {
                    // grows a little with the text size; the tab's large content viewer shows its full name and count
                    Text(badge).scaledFont(10, weight: .bold, relativeTo: .caption2).foregroundStyle(.white)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                        .padding(.horizontal, 4).background(Capsule().fill(theme.danger))
                        .offset(x: 10, y: -6)
                        .accessibilityIdentifier("badge-\(tab.name.lowercased())")
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 15)
            .padding(.bottom, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(badge.map { ShellUiCopy.shared.tabUnread(title: tab.title, count: $0) } ?? tab.title)
        .accessibilityAddTraits(active ? .isSelected : [])
        // the bar keeps its size at large text: a long press shows the tab's icon and name enlarged
        .largeContent(tab.title, appIcon: tab.icon)
        .accessibilityIdentifier(tab.identifier)
    }
}

/// RN Header on every tab: the avatar in a gradient pill (opens the side menu), name and "@user" (no reputation)
/// or "Login to customize your feed", then QR, search and boost per tab.
struct AppHeaderBar: View {
    let tab: AppTab
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @Environment(\.colorScheme) private var scheme
    @State private var account: Account?

    var body: some View {
        let username = session.state.username
        HStack(spacing: 0) {
            Button { app.openDrawer() } label: {
                ZStack(alignment: .trailing) {
                    LinearGradient(colors: scheme == .dark ? [Color(hex: 0x081C36), Color(hex: 0x43638E)] : [Color(hex: 0x2D5AA0), Color(hex: 0x357CE6)],
                                   startPoint: .leading, endPoint: .trailing)
                    Group {
                        if let username {
                            AvatarView(url: PostCard.companion.avatarUrl(account: username, proxyBase: app.env.proxyBase, size: "small"), size: 32)
                        } else {
                            Image(appIcon: .account, filled: true).resizable().scaledToFit().frame(width: 32, height: 32).foregroundStyle(.white)
                        }
                    }
                    .padding(.trailing, 12)
                }
                .frame(width: 68, height: 50)
                .clipShape(UnevenRoundedRectangle(bottomTrailingRadius: 34, topTrailingRadius: 34))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(username.map { ShellUiCopy.shared.accountOf(username: $0) } ?? ShellUiCopy.shared.ACCOUNT_SIGNED_OUT)
            .largeContent(username.map { ShellUiCopy.shared.accountOf(username: $0) } ?? ShellUiCopy.shared.ACCOUNT_SIGNED_OUT, appIcon: .account)
            .accessibilityIdentifier("account-menu")
            VStack(alignment: .leading, spacing: 0) {
                if let username {
                    if let name = account?.profile.displayName, account?.name == username {
                        Text(name).scaledFont(14, weight: .bold).foregroundStyle(theme.secondary).lineLimit(1)
                    }
                    Text("@\(username)").scaledFont(12).foregroundStyle(theme.secondary).accessibilityIdentifier("header-user")
                } else {
                    // secondary, not RN's light icon gray: readable at WCAG AA; three lines before it truncates
                    Text(ShellUiCopy.shared.LOGIN_TO_CUSTOMIZE_FEED).scaledFont(14).foregroundStyle(theme.secondary).lineLimit(3)
                        .minimumScaleFactor(0.8)
                        .accessibilityIdentifier("header-login")
                }
            }
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 0) {
                if tab.showQr { headerButton(.qrScan, ShellUiCopy.shared.QR_SCAN, "header-qr", theme.icon) { app.push(AppScreenCompanion.shared.of(route: .qrScan, arg: "")) } }
                if tab.showSearch { headerButton(.search, UiCopy.shared.SEARCH, "header-search", theme.icon) { app.push(AppScreenCompanion.shared.of(route: .searchResult, arg: "")) } }
                if tab.showsBoost(loggedIn: username != nil) {
                    headerButton(.boost, ShellUiCopy.shared.PERKS, "header-boost", theme.accent) { app.push(AppScreenCompanion.shared.of(route: .perks, arg: "")) }
                }
            }
            .padding(.trailing, 16)
        }
        .padding(.bottom, 4)
        .background(theme.background)
        .task(id: username) {
            guard let username else { account = nil; return }
            account = try? await app.env.api.accounts.getAccount(name: username, refresh: false)
        }
    }

    private func headerButton(_ icon: AppIcon, _ label: String, _ id: String, _ tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(appIcon: icon).scaledFont(20).foregroundStyle(tint).frame(minWidth: 32, minHeight: 32)
                // the header keeps room for the account name at the largest text sizes
                .dynamicTypeSize(...DynamicTypeSize.xxLarge).tapTarget() }
            .accessibilityLabel(label)
            // the icon stops growing: a long press shows it with its name at accessibility text sizes
            .largeContent(label, appIcon: icon)
            .accessibilityIdentifier(id)
    }
}

/// The one screen for every RN feature not built yet; it names the feature.
struct ComingSoonView: View {
    let title: String
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 8) {
            Text(title).scaledFont(20, weight: .bold).foregroundStyle(theme.text).accessibilityIdentifier("coming-soon-title")
            Text(ShellUiCopy.shared.COMING_SOON_TO_THE).scaledFont(14).foregroundStyle(theme.secondary)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("coming-soon")
    }
}

/// RN LoggedInContainer / the chats sign-in message.
struct LoginPromptView: View {
    let text: String
    let onLogin: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 16) {
            Image(appIcon: .person).scaledFont(72, relativeTo: .largeTitle).foregroundStyle(theme.icon)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge).accessibilityHidden(true)
            Text(text).scaledFont(15).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
            Button(action: onLogin) {
                Text(UiCopy.shared.LOGIN).scaledFont(15, weight: .bold).foregroundStyle(.white).padding(.horizontal, 32).padding(.vertical, 10)
                    .background(Capsule().fill(theme.accent))
            }
            .accessibilityIdentifier("login-prompt-button")
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("login-prompt")
    }
}

/// RN ErrorBoundary fallback. SwiftUI cannot catch a crash while rendering; screens show this when their setup fails.
struct ShellErrorView: View {
    let onRetry: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 8) {
            // README "Esie": sad Esie in place of RN's icon
            EsieView(.error).padding(.bottom, 4)
            Text(UiCopy.shared.SOMETHING_WRONG).scaledFont(20).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                .announces(UiCopy.shared.SOMETHING_WRONG)
            Text(ShellUiCopy.shared.SOMETHING_WRONG_ALT).scaledFont(15).foregroundStyle(theme.text).multilineTextAlignment(.center).padding(15)
            Button(UiCopy.shared.TRY_AGAIN_QUESTION, action: onRetry).accessibilityIdentifier("error-retry")
        }
        .padding(16)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("error-boundary")
    }
}

/// RN NoInternetConnection: a gray strip with "No connection!" at the bottom.
struct OfflineBanner: View {
    var body: some View {
        HStack(spacing: 5) {
            Image(appIcon: .info, filled: true).scaledFont(14).accessibilityHidden(true)
            Text(ShellUiCopy.shared.NO_CONNECTION).scaledFont(14, weight: .bold)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, minHeight: 45)
        // RN's 0x788187 under white text was 4:1; this gray (the toast's) keeps the look at AA
        .background(Color(hex: ContrastPalette.lightMessageGray).ignoresSafeArea(edges: .bottom))
        .accessibilityElement(children: .combine)
        .announces(ShellUiCopy.shared.NO_CONNECTION)
        .accessibilityIdentifier("offline-banner")
    }
}

/// RN ForegroundNotification over the app, from the shared UiMessages channel (toasts: ToastOverlay).
struct MessagesOverlay: View {
    @ObservedObject var ui: Observed<UiMessagesState>
    let onOpen: (AppScreen) -> Void
    /// A banner whose tap is more than one screen (a tab, a gated screen).
    var onEntry: (NavEntry) -> Void = { _ in }
    @Environment(\.colorScheme) private var scheme

    /// RN's gray darkened in light mode for white text at WCAG AA (the toast's gray)
    private var gray: Color { scheme == .dark ? Color(hex: 0x526D91) : Color(hex: ContrastPalette.lightMessageGray) }
    private var model: UiMessages { ui.model as! UiMessages }

    var body: some View {
        GeometryReader { geo in content(width: geo.size.width) }
    }

    private func content(width: CGFloat) -> some View {
        ZStack {
            if let banner = ui.state.banner {
                HStack(spacing: 16) {
                    // RN shows the actor's avatar; the name was validated by the push model
                    if !banner.avatarUser.isEmpty {
                        AvatarView(url: PostCard.companion.avatarUrl(account: banner.avatarUser, proxyBase: ImageProxy.companion.DEFAULT_BASE, size: "small"), size: 32)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(banner.title).scaledFont(14, weight: .bold).lineLimit(2)
                        if !banner.body.isEmpty { Text(banner.body).scaledFont(14).lineLimit(3) }
                    }
                }
                .foregroundStyle(.white)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(gray).shadow(color: .black.opacity(0.3), radius: 3, y: 5))
                .padding(.horizontal, 8)
                .frame(maxHeight: .infinity, alignment: .top)
                .onTapGesture {
                    model.dismiss(id: banner.id_)
                    if let entry = banner.entry { onEntry(entry) } else if let screen = banner.screen { onOpen(screen) }
                }
                .task(id: banner.id_) {
                    try? await Task.sleep(nanoseconds: UInt64(banner.durationMillis) * 1_000_000)
                    model.dismiss(id: banner.id_)
                }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .onEscape { model.dismiss(id: banner.id_) }
                .announces([banner.title, banner.body].filter { !$0.isEmpty }.joined(separator: ", "))
                .accessibilityIdentifier("banner")
            }
            // toasts are in their own window above every sheet (ToastOverlay)
        }
        .frame(width: width)
        .animation(.easeInOut(duration: 0.2), value: ui.state.banner?.id_)
    }
}
