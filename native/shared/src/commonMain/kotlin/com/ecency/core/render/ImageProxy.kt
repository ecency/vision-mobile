package com.ecency.core.render

import com.ecency.core.hive.Base58
import com.ecency.core.render.js.Js
import com.ecency.core.render.url.FormUrlEncoded
import com.ecency.core.render.url.WebUrl

/**
 * Image proxy URLs, ported from render-helper's `proxify-image-src.ts`. The base58 hash of the source
 * URL is the proxy's cache key, so these URLs must stay byte-identical to the web and RN apps.
 */
class ImageProxy(val base: String = DEFAULT_BASE) {

    /** `proxifyImageSrc(url, width, height, 'match', { blur, forceProxy })`. Empty for unusable URLs. */
    fun proxify(url: String?, width: Int = 0, height: Int = 0, blur: Boolean = false, forceProxy: Boolean = false): String =
        proxifyForFormat(url, width, height, "match", blur, forceProxy)

    /** `buildSrcSet(url)`. */
    fun srcSet(url: String?): String = srcSetForFormat(url, "match")

    fun srcSetForFormat(url: String?, format: String): String {
        if (url.isNullOrEmpty()) return ""
        val proxyPrefix = "$base/p/"
        val result = if (url.startsWith(proxyPrefix)) {
            val rest = url.substring(proxyPrefix.length)
            val q = rest.indexOf('?')
            val pHash = extractPHash(url) ?: (if (q >= 0) rest.substring(0, q) else rest)
            SRCSET_WIDTHS.joinToString(", ") { w -> "$base/p/$pHash?format=$format&mode=fit&width=$w ${w}w" }
        } else {
            SRCSET_WIDTHS.mapNotNull { w ->
                proxifyForFormat(url, w, 0, format).takeIf { it.isNotEmpty() }?.let { "$it ${w}w" }
            }.joinToString(", ")
        }
        if (format != "match" && result.isNotEmpty() && !result.split(',').all { "format=$format" in it }) return ""
        return result
    }

    fun extractPHash(url: String): String? {
        val prefix = listOf("$base/p/", LEGACY_BASE + "/p/").firstOrNull { url.startsWith(it) } ?: return null
        val hash = url.substring(prefix.length).substringBefore('?')
        return hash.removeSuffix(".webp").let { if (it.length == hash.length) hash.removeSuffix(".png") else it }
    }

    private fun proxifyForFormat(
        url: String?,
        width: Int,
        height: Int,
        format: String,
        blur: Boolean = false,
        forceProxy: Boolean = false,
    ): String {
        if (url.isNullOrEmpty() || !WebUrl.canParse(url)) return ""
        // base58 is quadratic in the input length; post bodies are user-authored.
        if (url.length > MAX_PROXIED_URL_LENGTH) return ""

        val routeThroughProxy = width > 0 || height > 0 || blur || forceProxy

        if (isLegacyForeignProxyUrl(url) && !(isLegacySizedProxyUrl(url) && routeThroughProxy)) {
            return url.replaceFirst("https://images.hive.blog", base).replaceFirst("https://steemitimages.com", base)
        }

        if (url.startsWith("$LEGACY_BASE/") && !routeThroughProxy) {
            return url.replaceFirst(LEGACY_BASE, base)
        }

        val realUrl = extractLegacySizedSource(url) ?: getLatestUrl(url)
        val pHash = extractPHash(realUrl)

        // querystring.stringify in insertion order: format, mode, width, height, blur.
        val qs = buildString {
            append("format=").append(format).append("&mode=fit")
            if (width > 0) append("&width=").append(width)
            if (height > 0) append("&height=").append(height)
            if (blur) append("&blur=1")
        }

        if (pHash != null) return "$base/p/$pHash?$qs"
        return "$base/p/${Base58.encode(Js.utf8(realUrl))}?$qs"
    }

    /** `buildPictureSources(rawUrl)`: the avif and webp `<source>` srcsets, or null when ineligible. */
    fun pictureSources(rawUrl: String?): Pair<String, String>? {
        if (!isPictureEligibleRawUrl(rawUrl)) return null
        val avif = srcSetForFormat(rawUrl, "avif")
        val webp = srcSetForFormat(rawUrl, "webp")
        if (avif.isEmpty() || webp.isEmpty()) return null
        return avif to webp
    }

    /**
     * The srcset web loads a body image from. [src] is render-helper's `<img src>`: when it is the proxified form
     * of a raw URL web wraps in `<picture>` (the hash decodes back to that URL), the avif or webp source;
     * otherwise the `<img>` srcset (format=match), as render-helper builds for gifs and already proxied URLs.
     */
    fun bodySrcSet(src: String, avif: Boolean): String {
        if (!src.startsWith("$base/p/")) return ""
        val hash = extractPHash(src)?.takeIf { it.length <= MAX_HASH_LENGTH }
        val raw = hash?.let { runCatching { Base58.decode(it).decodeToString() }.getOrNull() }
        val sources = raw?.takeIf { proxify(it, forceProxy = true) == src }?.let(::pictureSources) ?: return srcSet(src)
        return if (avif) sources.first else sources.second
    }

    /** Whether avif/webp renditions are safe for a raw image URL (static raster, not already proxied). */
    fun isPictureEligibleRawUrl(rawUrl: String?): Boolean = isPictureEligible(rawUrl, mayUnwrapLegacy = true)

    private fun isPictureEligible(rawUrl: String?, mayUnwrapLegacy: Boolean): Boolean {
        if (rawUrl.isNullOrEmpty()) return false
        val u = WebUrl.parse(rawUrl) ?: return false
        if (u.protocol != "http:" && u.protocol != "https:") return false
        if (mayUnwrapLegacy && isLegacySizedProxyUrl(rawUrl)) {
            val nested = extractLegacySizedSource(rawUrl)
            return nested != null && isPictureEligible(nested, mayUnwrapLegacy = false)
        }
        val host = "${u.protocol}//${u.host}"
        val isProxyHost = host == base || host == LEGACY_BASE
        if (isProxyHost && (u.pathname.startsWith("/p/") || u.pathname.startsWith("/u/") || SIZED_PROXY_PATH.containsMatchIn(u.pathname))) {
            return false
        }
        return STATIC_RASTER_PATH_EXT.containsMatchIn(u.pathname)
    }

    companion object {
        const val DEFAULT_BASE = "https://i.ecency.com"

        /** Ecency's own proxies, whose sized variants web requests; other image servers keep their URLs. */
        private val SIZED_BASES = listOf(DEFAULT_BASE, "https://img.ecency.com")

        /**
         * The URL to load a body image [src] (render-helper's `<img src>`) drawn [widthPx] device pixels wide: the
         * srcset entry a phone browser picks on web (smallest width that covers it, else 1280), so the CDN has it
         * warm. AVIF, or WebP when [avif] is false, where web wraps the image in `<picture>`; format=match for
         * the rest (gifs, files without a raster extension), as web. Other hosts and routes come back unchanged.
         */
        fun bodyVariant(src: String, widthPx: Int, avif: Boolean): String {
            val base = SIZED_BASES.firstOrNull { src.startsWith("$it/p/") } ?: return src
            return pickWidth(ImageProxy(base).bodySrcSet(src, avif), widthPx) ?: src
        }

        private fun pickWidth(srcSet: String, widthPx: Int): String? {
            val candidates = srcSet.split(", ").mapNotNull { c ->
                val w = c.substringAfterLast(' ').removeSuffix("w").toIntOrNull() ?: return@mapNotNull null
                w to c.substringBeforeLast(' ')
            }
            return (candidates.filter { it.first >= widthPx }.minByOrNull { it.first } ?: candidates.maxByOrNull { it.first })?.second
        }

        private const val LEGACY_BASE = "https://images.ecency.com"
        private const val MAX_PROXIED_URL_LENGTH = 2048
        // about base58 of MAX_PROXIED_URL_LENGTH bytes: a longer hash is not decoded and keeps the match srcset.
        private const val MAX_HASH_LENGTH = 2800
        private val SRCSET_WIDTHS = listOf(320, 600, 800, 1024, 1280)
        private val LEGACY_SIZED_PROXY_RE = Regex("^https://(?:images\\.hive\\.blog|steemitimages\\.com)/\\d+x\\d+/(.+)$")
        private val STATIC_RASTER_PATH_EXT = Regex("\\.(?:jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
        private val SIZED_PROXY_PATH = Regex("^/\\d+x\\d+/")
        private val HTTP_PREFIX = Regex("https?://")

        fun isLegacySizedProxyUrl(url: String?): Boolean = url != null && LEGACY_SIZED_PROXY_RE.matches(url)

        private fun isLegacyForeignProxyUrl(url: String) =
            (url.startsWith("https://images.hive.blog/") && !url.startsWith("https://images.hive.blog/D")) ||
                (url.startsWith("https://steemitimages.com/") && !url.startsWith("https://steemitimages.com/D"))

        /** The last `http(s)://` URL in the string, as render-helper's `getLatestUrl`. */
        fun getLatestUrl(str: String): String =
            HTTP_PREFIX.replace(str) { "\n" + it.value }.trim().split('\n').last()

        /** The nested source of `images.hive.blog/<WxH>/<inner>`, normalised the way the imagehoster does. */
        private fun extractLegacySizedSource(url: String): String? {
            val m = LEGACY_SIZED_PROXY_RE.matchEntire(url) ?: return null
            val addressable = m.groupValues[1].substringBefore('#')
            val qIndex = addressable.indexOf('?')
            val path = if (qIndex >= 0) addressable.substring(0, qIndex) else addressable
            val query = if (qIndex >= 0) addressable.substring(qIndex + 1) else ""
            val inner = WebUrl.parse(path.trimEnd('/')) ?: return null
            if (query.isNotEmpty()) {
                for ((key, value) in FormUrlEncoded.parse(query)) inner.appendSearchParam(key, value)
            }
            return inner.href
        }
    }
}
