import EcencyCore
import Foundation

/// A screen on the navigation stack.
enum Destination: Hashable {
    case post(author: String, permlink: String)
    /// An account's blog feed.
    case profile(author: String)
    /// A ranked tag or community feed.
    case feed(tag: String, sort: String, title: String)
    /// Debug menu: the renderer demo post.
    case rendererDemo
}

/// What the UI does for a tap, decided from the shared `LinkRouter` result. Pure, so it is unit tested.
enum NavDecision: Equatable {
    case push(Destination)
    /// SFSafariViewController; http and https only.
    case browser(URL)
    /// The video sheet, restricted to this embed URL's host.
    case video(URL)
    /// A hive:// or ecency:// link for the auth link model, which asks before it signs or shares anything.
    case appLink(String)
    /// An alert with this message.
    case unsupported(String)
    /// Nothing to open (an in-page jump outside the post screen).
    case nothing

    static func from(_ route: Route) -> NavDecision {
        switch route {
        case let r as RoutePost:
            return .push(.post(author: r.author, permlink: r.permlink))
        case let r as RouteProfile:
            return .push(.profile(author: r.author))
        case let r as RouteFeed:
            guard let ranked = r.source as? FeedSourceRanked else { return .unsupported(L.Native.Ios.Navigation.feedUnavailable) }
            return .push(.feed(tag: ranked.tag, sort: ranked.sort.apiValue, title: r.title))
        case let r as RouteExternalBrowser:
            guard let url = webURL(r.url) else { return .unsupported(L.Native.Ios.Navigation.linkCannotOpen) }
            return .browser(url)
        case let r as RouteVideoPlayer:
            guard let url = URL(string: r.embedUrl), url.scheme?.lowercased() == "https", url.host?.isEmpty == false else {
                return .unsupported(L.Native.Ios.Navigation.videoCannotPlay)
            }
            return .video(url)
        case let r as RouteAppLink:
            return .appLink(r.uri)
        case is RouteUnsupported:
            return .unsupported(LinkCopy.shared.UNSUPPORTED_VERSION)
        case is RouteInPage:
            // an #id jump: the post screen scrolls before routing; anywhere else it does nothing
            return .nothing
        default:
            return .unsupported(LinkCopy.shared.UNSUPPORTED_VERSION)
        }
    }

    static func from(_ target: LinkTarget) -> NavDecision { from(LinkRouter.shared.route(target: target)) }

    /// http(s) with a host, else nil. The shared router already checked; this is defence in depth.
    static func webURL(_ string: String) -> URL? {
        guard let url = URL(string: string), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", url.host?.isEmpty == false else { return nil }
        return url
    }
}

/// Which navigations the video sheet's web view may perform: the embed URL's host only (https; with or
/// without `www.`, so youtube.com/embed redirecting to www.youtube.com still plays),
/// plus `about:blank` / `about:srcdoc` frames the players create. Everything else is cancelled.
enum VideoEmbedPolicy {
    static func allows(_ url: URL?, embed: URL) -> Bool {
        guard let url else { return false }
        let scheme = url.scheme?.lowercased()
        if scheme == "about" { return url.absoluteString == "about:blank" || url.absoluteString == "about:srcdoc" }
        guard scheme == "https", let host = url.host?.lowercased(), let embedHost = embed.host?.lowercased() else { return false }
        return SafeTarget.shared.samePlayerHost(host: host, embedHost: embedHost)
    }
}

extension Destination {
    /// Feed source for `.profile` and `.feed`; nil for a post.
    var feedSource: FeedSource? {
        switch self {
        case .post, .rendererDemo: return nil
        case let .profile(author): return LinkRouter.shared.profileFeed(author: author)
        case let .feed(tag, sort, _):
            let ranked = RankedSort.entries.first { $0.apiValue == sort } ?? RankedSort.trending
            return FeedSourceRanked(sort: ranked, tag: tag)
        }
    }

    var title: String {
        switch self {
        case let .post(author, _): return "@\(author)"
        case let .profile(author): return "@\(author)"
        case let .feed(_, _, title): return title
        case .rendererDemo: return "Renderer demo"
        }
    }
}

extension Destination {
    /// The shared screen for this destination; nil for the debug renderer demo.
    var screen: AppScreen? {
        switch self {
        case let .post(author, permlink): return AppScreenPost(author: author, permlink: permlink)
        case let .profile(author): return AppScreenProfile(author: author)
        // a tag feed is the tag screen, a community's the community page
        case let .feed(_, _, title): return feedSource.map { NavEntry.companion.feedScreen(source: $0, title: title) }
        case .rendererDemo: return nil
        }
    }
}
