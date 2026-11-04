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

extension WavyDash {
    @MainActor private static var tabImages: [CGFloat: UIImage] = [:]

    /// The mark as a 26 pt template image at the display's scale, for the tab bar (the bar tints it).
    @MainActor static func tabImage(scale: CGFloat) -> UIImage {
        if let hit = tabImages[scale] { return hit }
        let renderer = ImageRenderer(content: WavyDash().fill(Color.black).frame(width: 26, height: 26))
        renderer.scale = scale
        let image = (renderer.uiImage ?? UIImage()).withRenderingMode(.alwaysTemplate)
        tabImages[scale] = image
        return image
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
