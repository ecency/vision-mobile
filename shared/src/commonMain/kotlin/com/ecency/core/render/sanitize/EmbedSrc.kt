package com.ecency.core.render.sanitize

import com.ecency.core.render.js.Js
import com.ecency.core.render.methods.JsRegex
import com.ecency.core.render.methods.g
import com.ecency.core.render.url.WebUrl

/**
 * render-helper `consts/embed-hosts.const.ts`: the hosts (and per-host embed paths) an iframe
 * may point at. `isAllowedEmbedSrc` parses with the WHATWG URL parser in JS; here that is the
 * shared [WebUrl] port (golden-tested against Node's URL, IDNA validity included), so the
 * allow list and every other URL decision in the renderer read hosts the same way.
 */
object EmbedSrc {
    // 2.5.45: ipfs.skatehive.app is no embed host (iframe() turns those sources into a <video>).
    internal val ALLOWED_HOSTS = setOf(
        "www.youtube.com", "youtube.com", "www.youtube-nocookie.com", "youtube-nocookie.com",
        "player.vimeo.com", "player.twitch.tv", "emb.d.tube", "play.3speak.tv", "3speak.tv", "audio.3speak.tv",
        "www.loom.com", "open.spotify.com", "w.soundcloud.com", "www.bitchute.com", "bitchute.com",
        "www.rumble.com", "rumble.com", "www.brighteon.com", "brighteon.com", "www.vimm.tv", "brandnewtube.com",
        "lbry.tv", "odysee.com", "www.skatehype.com", "skatehype.com", "archive.org",
        "embed.truvvl.com", "aureal-embed.web.app", "www.aureal-embed.web.app",
    )
    internal val ALLOWED_SUFFIXES = listOf(".dapplr.in")

    private fun embedPrefix(path: String) = path.startsWith("/embed/")
    private fun re(pattern: String, ignoreCase: Boolean = false): (String) -> Boolean {
        // JS semantics on both platforms (JsRegex): no Unicode case folding (`ſ` is not `s`), `$` only at the end
        val r = JsRegex.compile(pattern, ignoreCase)
        return { p -> r.containsMatchIn(p) }
    }
    private val odysee = re("^/(?:\\$|%24)/embed/")

    // 2.5.45 (#1919): every listed host has a path shape, mirroring iframe()'s provider patterns.
    private val PATH_PATTERNS: Map<String, (String) -> Boolean> = mapOf(
        "www.youtube.com" to ::embedPrefix,
        "youtube.com" to ::embedPrefix,
        "www.youtube-nocookie.com" to ::embedPrefix,
        "youtube-nocookie.com" to ::embedPrefix,
        "player.vimeo.com" to { p -> p.startsWith("/video/") },
        "player.twitch.tv" to { p -> p == "/" },
        "emb.d.tube" to { p -> p == "/" },
        "play.3speak.tv" to { p -> p.startsWith("/watch") || p.startsWith("/embed") },
        "open.spotify.com" to { p -> p.startsWith("/embed/") || p.startsWith("/embed-podcast/") },
        "www.loom.com" to ::embedPrefix,
        "www.bitchute.com" to ::embedPrefix,
        "bitchute.com" to ::embedPrefix,
        "www.rumble.com" to ::embedPrefix,
        "rumble.com" to ::embedPrefix,
        "www.brighteon.com" to ::embedPrefix,
        "brighteon.com" to ::embedPrefix,
        "odysee.com" to odysee,
        "lbry.tv" to odysee,
        "3speak.tv" to { p -> p == "/watch" || p == "/embed" },
        "audio.3speak.tv" to { p -> p == "/play" },
        "w.soundcloud.com" to { p -> p == "/player/" },
        "archive.org" to re("^/embed/[^/]+$"),
        "www.vimm.tv" to re("^/[A-Za-z0-9_-]+(?:/embed)?/?$"),
        "brandnewtube.com" to re("^/embed/[a-z0-9]+$", ignoreCase = true),
        "www.skatehype.com" to { p -> p == "/ifplay.php" },
        "skatehype.com" to { p -> p == "/ifplay.php" },
        "embed.truvvl.com" to re("^/@[A-Za-z0-9_.-]+/"),
        "aureal-embed.web.app" to re("^/[0-9]+$"),
        "www.aureal-embed.web.app" to re("^/[0-9]+$"),
    )
    private val SUFFIX_PATH_PATTERNS: Map<String, (String) -> Boolean> = mapOf(
        ".dapplr.in" to { p -> p.startsWith("/file/dapplr-videos/") },
    )

    /** Whether render-helper itself limits the paths of [host] (lower case). */
    internal fun hasPathPattern(host: String): Boolean = host in PATH_PATTERNS

    /** U+0001 to U+0020: what the URL parser strips at the ends (NUL is not, the parser keeps it). */
    private fun trimC0AndSpace(v: String): String {
        var start = 0
        var end = v.length
        while (start < end && v[start].code in 1..0x20) start++
        while (end > start && v[end - 1].code in 1..0x20) end--
        return v.substring(start, end)
    }

    /**
     * render-helper `isAllowedEmbedSrc`. 2.5.45 (#1919): only C0 controls and space are trimmed, a literal `https://`
     * is required and NUL refused, so what is checked is what a browser loads.
     */
    fun isAllowedEmbedSrc(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val trimmed = trimC0AndSpace(value)
        if (!Js.regionMatchesLower(trimmed, 0, "https://") || trimmed.indexOf('\u0000') >= 0) return false
        val url = WebUrl.parse(trimmed) ?: return false
        if (url.protocol != "https:") return false
        val host = url.hostname.lowercase()
        if (host in ALLOWED_HOSTS) {
            val pattern = PATH_PATTERNS[host]
            return pattern == null || pattern(url.pathname)
        }
        // a real label must precede the suffix
        val suffix = ALLOWED_SUFFIXES.firstOrNull { host.endsWith(it) && host.length > it.length && !host.startsWith(".") } ?: return false
        val pattern = SUFFIX_PATH_PATTERNS[suffix]
        return pattern == null || pattern(url.pathname)
    }

    private val SCHEME_SLASHES = JsRegex.compile("^(?:https?:)?//", ignoreCase = true)
    private val YOUTUBE_HOST = JsRegex.compile("^https://(?:m\\.)?youtube\\.com/", ignoreCase = true)
    private val SPEAK_HOST = JsRegex.compile("^https://(?:play\\.)?3speak\\.[a-z]+/", ignoreCase = true)
    private val WWW_YOUTUBE = JsRegex.compile("^https://www\\.youtube\\.com/", ignoreCase = true)
    private val YOUTUBE_EMBED = JsRegex.compile("^(https://www\\.youtube\\.com)/embed/", ignoreCase = true)
    private val YOUTUBE_SHORT = JsRegex.compile("^https://www\\.youtube\\.com/shorts/([A-Za-z0-9_-]{11})/?(?=[?#]|$)", ignoreCase = true)

    /**
     * 2.5.45 `canonicalEmbedSrc`: the spelling iframe() normalises an embed to (https, www.youtube.com, play.3speak.tv,
     * lower-case /embed/, /shorts/<id> as /embed/<id>, BitChute and Brighteon pages as their embed route). The
     * sanitizer gives a refused src this spelling one more try.
     */
    fun canonicalEmbedSrc(src: String): String {
        var s = Js.trim(src)
        s = SCHEME_SLASHES.replaceFirst(s, "https://")
        s = YOUTUBE_HOST.replaceFirst(s, "https://www.youtube.com/")
        s = SPEAK_HOST.replaceFirst(s, "https://play.3speak.tv/")
        if (WWW_YOUTUBE.containsMatchIn(s)) {
            s = YOUTUBE_EMBED.find(s)?.let { m -> m.groupValues[1] + "/embed/" + s.substring(m.range.last + 1) } ?: s
            YOUTUBE_SHORT.find(s)?.let { s = "https://www.youtube.com/embed/" + it.groupValues[1] }
        }
        com.ecency.core.render.methods.Consts.BITCHUTE_REGEX.find(s)?.let { s = "https://www.bitchute.com/embed/${it.g(1)}/" }
        com.ecency.core.render.methods.Consts.BRIGHTEON_REGEX.find(s)?.let { s = "https://www.brighteon.com/embed/${it.g(2)}" }
        return s
    }
}
