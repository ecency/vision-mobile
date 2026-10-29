package com.ecency.core.render.blocks

import com.ecency.core.render.ImageProxy
import com.ecency.core.render.sanitize.EmbedSrc
import com.ecency.core.render.url.WebUrl

/**
 * Validation for everything a tap can reach. render-helper keeps authored `data-*` attributes, so
 * every value that becomes a [LinkTarget], an embed URL or an image URL is checked here, once.
 * Each function returns null for an invalid value; the builder then shows the text without a link
 * (text is never dropped).
 */
object SafeTarget {
    /** Hive account name: 3 to 16 chars, dot-separated segments of 3+ chars that start with a letter, end alphanumeric, no `--`. */
    fun account(name: String?): String? {
        val n = name ?: return null
        if (n.length !in 3..16) return null
        for (seg in n.split('.')) {
            if (seg.length < 3 || seg[0] !in 'a'..'z') return null
            if (!seg.last().let { it in 'a'..'z' || it in '0'..'9' }) return null
            if (seg.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-') } || "--" in seg) return null
        }
        return n
    }

    /** Hive tags as render-helper links them (`#[-a-z\d]+`), lower-cased, at most 40 chars. */
    fun tag(tag: String?): String? {
        val t = tag?.trim()?.lowercase() ?: return null
        return t.takeIf { it.length in 1..40 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }
    }

    /** Feed filters (`created`, `trending`, ...): letters and underscores only, else no filter. */
    fun filter(filter: String?): String? = filter?.trim()?.takeIf { it.length in 1..24 && it.all { c -> c in 'a'..'z' || c == '_' } }

    fun community(name: String?): String? {
        val n = name ?: return null
        return n.takeIf { it.startsWith("hive-") && it.length in 6..15 && it.substring(5).all { c -> c in '0'..'9' } }
    }

    fun permlink(permlink: String?): String? = permlink?.takeIf { it.length in 1..256 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }

    fun youtubeId(id: String?): String? = id?.takeIf { it.length == 11 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' } }

    /** http(s) with a host: [LinkTarget.External]; `hive://` and `ecency://`: [LinkTarget.AppUri]; anything else: null. */
    fun external(url: String?): LinkTarget? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (u.any { it.code < 0x20 || it == '\u007F' }) return null
        val lower = u.lowercase()
        if (lower.startsWith("hive://") || lower.startsWith("ecency://")) {
            return if (u.length > lower.indexOf("://") + 3 && u.none { it.isWhitespace() }) LinkTarget.AppUri(u) else null
        }
        return httpUrl(u)?.let { LinkTarget.External(it) }
    }

    /**
     * The URL when it is absolute http or https with a host. A light check (scheme, then a host
     * of URL host characters before any path); full WHATWG parsing of every body image URL was the
     * builder's main cost.
     */
    fun httpUrl(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val start = when {
            u.startsWith("https://", ignoreCase = true) -> 8
            u.startsWith("http://", ignoreCase = true) -> 7
            else -> return null
        }
        if (u.any { it.code < 0x20 || it == '\u007F' }) return null
        var end = start
        while (end < u.length && u[end] != '/' && u[end] != '?' && u[end] != '#' && u[end] != '\\') end++
        var host = u.substring(start, end)
        // userinfo ("ecency.com@evil.example") reads like one site and opens another: never a tap target
        if ('@' in host) return null
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            return u.takeIf { close > 1 && host.substring(1, close).all { it.isLetterOrDigit() || it == ':' || it == '.' } }
        }
        val colon = host.indexOf(':')
        if (colon >= 0) {
            if (!host.substring(colon + 1).all { it in '0'..'9' }) return null
            host = host.substring(0, colon)
        }
        if (host.isEmpty() || host.startsWith(".")) return null
        return u.takeIf { host.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' || it == '%' || it == '~' } }
    }

    fun author(author: String?): LinkTarget.Author? = account(author)?.let { LinkTarget.Author(it) }

    fun tagTarget(tag: String?, filter: String?): LinkTarget.Tag? = tag(tag)?.let { LinkTarget.Tag(it, filter(filter)) }

    fun communityTarget(community: String?, filter: String?): LinkTarget.Community? =
        community(community)?.let { LinkTarget.Community(it, filter(filter)) }

    fun post(author: String?, permlink: String?): LinkTarget.Post? {
        val a = account(author) ?: return null
        val p = permlink(permlink) ?: return null
        return LinkTarget.Post(a, p)
    }

    fun youtube(id: String?, startSeconds: Int): LinkTarget.Youtube? = youtubeId(id)?.let { LinkTarget.Youtube(it, maxOf(0, startSeconds)) }

    /**
     * An iframe or player URL the app may load: render-helper's embed allowlist, plus a player path rule
     * for every host it accepts whole (those sites also serve full pages: logins, uploads), so the player
     * never opens such a page as an "embed". The rules follow the embed URLs render-helper's iframe
     * transform keeps (its `*_EMBED_REGEX` shapes); an allowlisted host without a rule is refused.
     */
    fun embed(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!EmbedSrc.isAllowedEmbedSrc(u)) return null
        val parsed = WebUrl.parse(u) ?: return null
        val host = parsed.hostname.lowercase()
        if (EmbedSrc.hasPathPattern(host)) return u
        val rule = PLAYER_PATHS[host] ?: PLAYER_PATHS.entries.firstOrNull { (h, _) -> h.startsWith(".") && host.endsWith(h) }?.value
        return u.takeIf { rule != null && rule(parsed.pathname) }
    }

    /**
     * Player paths of the allowlisted hosts render-helper accepts on any path (keys starting with "."
     * are host suffixes). Hosts render-helper limits itself are covered by [EmbedSrc.isAllowedEmbedSrc].
     */
    private val VIMM_PLAYER = Regex("^/[^/]+(/embed)?/?$")
    private val BRAND_NEW_TUBE_PLAYER = Regex("^/embed/[A-Za-z0-9]+$")
    private val AUREAL_PLAYER = Regex("^/[0-9]+$")

    private val PLAYER_PATHS: Map<String, (String) -> Boolean> = mapOf(
        // ARCH_REGEX: /embed/<item>
        "archive.org" to { p -> p.startsWith("/embed/") },
        // SPEAK_EMBED_REGEX: /embed?v= or /watch?v=
        "3speak.tv" to { p -> p == "/embed" || p.startsWith("/embed/") || p == "/watch" || p.startsWith("/watch/") },
        // SPEAK_AUDIO_EMBED_REGEX: /play?...
        "audio.3speak.tv" to { p -> p == "/play" },
        // SOUNDCLOUD_EMBED_REGEX: /player/?url=...
        "w.soundcloud.com" to { p -> p == "/player/" || p == "/player" },
        // Vimm: the channel page /<channel> (what render-helper's spec embeds) or /<channel>/embed
        "www.vimm.tv" to { p -> VIMM_PLAYER.matches(p) },
        // BRAND_NEW_TUBE_REGEX: /embed/<id>
        "brandnewtube.com" to { p -> BRAND_NEW_TUBE_PLAYER.matches(p) },
        // LBRY_REGEX: /$/embed/...
        "lbry.tv" to { p -> p.startsWith("/$/embed/") || p.startsWith("/%24/embed/") },
        // SKATEHIVE_IPFS_REGEX: /ipfs/<cid> (a video tag)
        "ipfs.skatehive.app" to { p -> p.startsWith("/ipfs/") && p.length > 6 },
        // SKATEHYPE_EMBED_REGEX: /ifplay.php?v=<n>
        "www.skatehype.com" to { p -> p == "/ifplay.php" },
        "skatehype.com" to { p -> p == "/ifplay.php" },
        // TRUVVL_REGEX: /@<user>/...
        "embed.truvvl.com" to { p -> p.startsWith("/@") },
        // AUREAL_EMBED_REGEX: /<number>
        "aureal-embed.web.app" to { p -> AUREAL_PLAYER.matches(p) },
        "www.aureal-embed.web.app" to { p -> AUREAL_PLAYER.matches(p) },
        // DAPPLR_REGEX: /file/dapplr-videos/...
        ".dapplr.in" to { p -> p.startsWith("/file/dapplr-videos/") },
    )

    /** Whether [host] (lower case; a ".suffix" for suffix entries) has a player path rule, here or in render-helper's list. */
    internal fun hasPlayerRule(host: String): Boolean =
        EmbedSrc.hasPathPattern(host) || host in PLAYER_PATHS

    /**
     * Whether a player frame on [host] stays on the embed's site [embedHost]: the same host, or the same
     * host with or without `www.` (youtube.com/embed redirects to www.youtube.com, www.rumble.com to rumble.com).
     */
    fun samePlayerHost(host: String, embedHost: String): Boolean {
        val a = host.lowercase().removePrefix("www.")
        val b = embedHost.lowercase().removePrefix("www.")
        return a.isNotEmpty() && a == b
    }

    /** A body image source: http(s) only. */
    fun image(src: String?): String? = httpUrl(src)

    /** A thumbnail already served by the image proxy; other URLs would leak the reader to the author's host. */
    fun proxiedImage(src: String?, proxyBase: String): String? {
        val u = httpUrl(src) ?: return null
        return u.takeIf { PROXY_HOSTS.any { h -> u.startsWith(h) } || u.startsWith("$proxyBase/") }
    }

    /** Routes [url] (e.g. the first metadata image) through the image proxy; null when unusable. */
    fun proxify(url: String?, proxyBase: String): String? {
        val u = httpUrl(url) ?: return null
        proxiedImage(u, proxyBase)?.let { return it }
        return ImageProxy(proxyBase).proxify(u).takeIf { it.startsWith("$proxyBase/") }
    }

    private val PROXY_HOSTS = listOf("https://images.ecency.com/", "https://i.ecency.com/")
}
