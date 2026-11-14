package com.ecency.core.render

/**
 * The Accept header the apps send to Ecency's image proxies (README "Image formats"). A `format=match` or `/u/` URL
 * is answered in AVIF or WebP only when the request names that type, and the CDN keeps one copy per group of
 * clients: AVIF, WebP, or neither. It puts a client in a group only for a bare list item (lower case, no spaces, no
 * parameters), so the header is exactly one of the two below. An animated source comes back as animated WebP even
 * when AVIF is named (the proxy cannot write animated AVIF).
 */
object ImageAccept {
    /** The request header's name. */
    const val NAME = "Accept"
    const val AVIF = "image/avif,image/webp,*/*"
    const val WEBP = "image/webp,*/*"

    /**
     * The header for [url]: [AVIF] when the platform decodes AVIF ([avif]), [WEBP] when it decodes WebP, still and
     * animated ([webp]); null for other hosts, or when it decodes neither (the request goes as before).
     */
    fun header(url: String, avif: Boolean, webp: Boolean): String? {
        if (!isProxy(url)) return null
        return when {
            avif -> AVIF
            webp -> WEBP
            else -> null
        }
    }

    private fun isProxy(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val authority = url.substring("https://".length).substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.lowercase() in HOSTS
    }

    private val HOSTS = setOf("i.ecency.com", "images.ecency.com", "img.ecency.com")
}
