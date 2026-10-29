package com.ecency.core.render.sanitize

import com.ecency.core.render.js.Js
import com.ecency.core.render.url.WebUrl

/**
 * render-helper `consts/embed-hosts.const.ts`: the hosts (and per-host embed paths) an iframe
 * may point at. `isAllowedEmbedSrc` parses with the WHATWG URL parser in JS; here that is the
 * shared [WebUrl] port (golden-tested against Node's URL, IDNA validity included), so the
 * allow list and every other URL decision in the renderer read hosts the same way.
 */
object EmbedSrc {
    internal val ALLOWED_HOSTS = setOf(
        "www.youtube.com", "youtube.com", "www.youtube-nocookie.com", "youtube-nocookie.com",
        "player.vimeo.com", "player.twitch.tv", "emb.d.tube", "play.3speak.tv", "3speak.tv", "audio.3speak.tv",
        "www.loom.com", "open.spotify.com", "w.soundcloud.com", "www.bitchute.com", "bitchute.com",
        "www.rumble.com", "rumble.com", "www.brighteon.com", "brighteon.com", "www.vimm.tv", "brandnewtube.com",
        "lbry.tv", "odysee.com", "ipfs.skatehive.app", "www.skatehype.com", "skatehype.com", "archive.org",
        "embed.truvvl.com", "aureal-embed.web.app", "www.aureal-embed.web.app",
    )
    internal val ALLOWED_SUFFIXES = listOf(".dapplr.in")

    private fun embedPrefix(path: String) = path.startsWith("/embed/")

    private val PATH_PATTERNS: Map<String, (String) -> Boolean> = mapOf(
        "www.youtube.com" to ::embedPrefix,
        "youtube.com" to ::embedPrefix,
        "www.youtube-nocookie.com" to ::embedPrefix,
        "youtube-nocookie.com" to ::embedPrefix,
        "player.vimeo.com" to { p -> p.startsWith("/video/") },
        "player.twitch.tv" to { p -> p == "/" },
        "emb.d.tube" to { p -> p == "/" },
        "play.3speak.tv" to { p -> p.startsWith("/watch") || p.startsWith("/embed") },
        "open.spotify.com" to ::embedPrefix,
        "www.loom.com" to ::embedPrefix,
        "www.bitchute.com" to ::embedPrefix,
        "bitchute.com" to ::embedPrefix,
        "www.rumble.com" to ::embedPrefix,
        "rumble.com" to ::embedPrefix,
        "www.brighteon.com" to ::embedPrefix,
        "brighteon.com" to ::embedPrefix,
        "odysee.com" to { p -> p.startsWith("/$/embed/") || p.startsWith("/%24/embed/") },
    )

    /** Whether render-helper itself limits the paths of [host] (lower case). */
    internal fun hasPathPattern(host: String): Boolean = host in PATH_PATTERNS

    /** render-helper `isAllowedEmbedSrc`. */
    fun isAllowedEmbedSrc(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val url = WebUrl.parse(Js.trim(value)) ?: return false
        if (url.protocol != "https:") return false
        val host = url.hostname.lowercase()
        val allowed = host in ALLOWED_HOSTS || ALLOWED_SUFFIXES.any { host.endsWith(it) }
        if (!allowed) return false
        val pattern = PATH_PATTERNS[host]
        return pattern == null || pattern(url.pathname)
    }
}
