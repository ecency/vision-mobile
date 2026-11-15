import SwiftUI
import WebKit
import EcencyCore

/// A web view whose web process ended (a crash, or memory pressure) loads its page again once; when it ends again the
/// screen shows [WebPageFailedView] with Retry instead of a blank page (issue #3701, review of #3706).
final class WebProcessRecovery {
    private var reloaded = false
    private let onFailed: () -> Void

    init(onFailed: @escaping () -> Void) { self.onFailed = onFailed }

    func ended(_ view: WKWebView) {
        if reloaded {
            onFailed()
            return
        }
        reloaded = true
        view.reload()
    }
}

/// The page could not be shown: the dApp browser's failed state, with Retry.
struct WebPageFailedView: View {
    let retry: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 12) {
            Image(appIcon: .offline).font(.system(size: 56)).foregroundStyle(theme.icon).accessibilityHidden(true)
            Text(BrowserCopy.shared.PAGE_LOAD_FAILED).scaledFont(16).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
                .announces(BrowserCopy.shared.PAGE_LOAD_FAILED)
            Button(BrowserCopy.shared.RETRY, action: retry).buttonStyle(.prominentFill).accessibilityIdentifier("web-retry")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityIdentifier("web-failed")
    }
}
