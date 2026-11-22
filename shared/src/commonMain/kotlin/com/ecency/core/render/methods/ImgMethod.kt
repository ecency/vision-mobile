package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
import com.ecency.core.render.dom.Element
import com.ecency.core.render.sanitize.ImageDimensions

/** render-helper `methods/img.method.ts`. */
internal object ImgMethod {
    const val IMAGE_SIZES = "(max-width: 768px) 100vw, 700px"

    /** `<picture>` with avif/webp sources around a displayed `<img>` (web only). */
    private fun wrapInPicture(el: Element, rawUrl: String, ctx: RenderContext) {
        val parent = el.parentNode ?: return
        if (Js.toLowerCase(parent.nodeName) == "picture") return
        val (avifSet, webpSet) = ctx.proxy.pictureSources(rawUrl) ?: return
        val doc = el.ownerDocument ?: return
        val sizes = el.getAttribute("sizes").let { if (it.isNullOrEmpty()) IMAGE_SIZES else it }
        val picture = doc.createElement("picture")
        val avif = doc.createElement("source")
        avif.setAttribute("type", "image/avif")
        avif.setAttribute("srcset", avifSet)
        avif.setAttribute("sizes", sizes)
        val webp = doc.createElement("source")
        webp.setAttribute("type", "image/webp")
        webp.setAttribute("srcset", webpSet)
        webp.setAttribute("sizes", sizes)
        parent.insertBefore(picture, el)
        picture.appendChild(avif)
        picture.appendChild(webp)
        picture.appendChild(el)
    }

    private fun isHttpUrl(s: String) = Js.startsWithCi(s, "http://") || Js.startsWithCi(s, "https://")

    /** `^<base>/\d+x\d+/` */
    private fun isSizedProxy(s: String, base: String): Boolean {
        if (!s.startsWith("$base/")) return false
        var i = base.length + 1
        val d1 = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == d1 || i >= s.length || s[i] != 'x') return false
        i++
        val d2 = i
        while (i < s.length && s[i] in '0'..'9') i++
        return i > d2 && i < s.length && s[i] == '/'
    }

    /** `^<base>/u/[^/]+/avatar(?:/[a-z]+)?$` */
    private fun isAvatarRoute(s: String, base: String): Boolean {
        val prefix = "$base/u/"
        if (!s.startsWith(prefix)) return false
        var i = prefix.length
        val start = i
        while (i < s.length && s[i] != '/') i++
        if (i == start || !s.startsWith("/avatar", i)) return false
        i += 7
        if (i == s.length) return true
        if (s[i] != '/') return false
        i++
        val l = i
        while (i < s.length && s[i] in 'a'..'z') i++
        return i > l && i == s.length
    }

    private fun splitWs(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        // JS "".split(/\s+/) is [""]; "a  b".split(/\s+/) is ["a","b"]; leading ws gives a leading "".
        while (i < s.length) {
            if (Js.isSpace(s[i])) {
                out.add(sb.toString())
                sb.setLength(0)
                while (i < s.length && Js.isSpace(s[i])) i++
            } else {
                sb.append(s[i])
                i++
            }
        }
        out.add(sb.toString())
        return out
    }

    fun img(el: Element, state: ImageState?, ctx: RenderContext) {
        val src = el.getAttribute("src") ?: ""
        val decodedSrc = Helper.decodeDomImageSrc(src)
        val reserved = if (ctx.options.preserveImageDimensions) {
            ImageDimensions.authorPixelSize(el.getAttribute("width"), el.getAttribute("height"))
        } else {
            null
        }
        for (attr in listOf("onerror", "dynsrc", "lowsrc", "width", "height")) el.removeAttribute(attr)

        val isInvalid = src.isEmpty() || decodedSrc.startsWith("javascript") || decodedSrc.startsWith("vbscript") || decodedSrc == "x"
        if (isInvalid) {
            el.removeAttribute("src")
            el.removeAttribute("srcset")
            el.removeAttribute("sizes")
            return
        }
        val isRelative = !isHttpUrl(decodedSrc) && !decodedSrc.startsWith("/")
        if (isRelative) {
            el.removeAttribute("src")
            el.removeAttribute("srcset")
            el.removeAttribute("sizes")
            return
        }
        if (reserved != null) {
            el.setAttribute("width", reserved.first)
            el.setAttribute("height", reserved.second)
        }
        el.setAttribute("itemprop", "image")

        val base = Helper.trimTrailingSlash(ctx.proxy.base)
        val classTokens = splitWs(el.getAttribute("class") ?: "")
        val isAvatar = isAvatarRoute(decodedSrc, base) || "er-author-link-image" in classTokens
        if (isAvatar) {
            el.setAttribute("loading", "lazy")
            el.setAttribute("decoding", "async")
        }
        val imageIndex = if (state == null || isAvatar) 3 else (state.imageCount ?: if (state.firstImageFound) 1 else 0)
        if (state != null && !isAvatar) {
            state.imageCount = imageIndex + 1
            state.firstImageFound = true
        }
        if (!isAvatar) {
            when (imageIndex) {
                0 -> {
                    el.setAttribute("loading", "eager")
                    el.setAttribute("fetchpriority", "high")
                }
                1 -> {
                    el.setAttribute("loading", "eager")
                    el.setAttribute("decoding", "async")
                }
                else -> {
                    el.setAttribute("loading", "lazy")
                    el.setAttribute("decoding", "async")
                }
            }
        }
        if (isAvatar || imageIndex != 0) el.removeAttribute("fetchpriority")

        val cls = el.getAttribute("class") ?: ""
        val shouldReplace = !cls.contains("no-replace")
        if (shouldReplace) {
            val sources = bodyImageSources(src, ctx)
            if (sources != null) {
                if (sources.src != src) el.setAttribute("src", sources.src)
                if (sources.srcSet.isNotEmpty()) {
                    el.setAttribute("srcset", sources.srcSet)
                    el.setAttribute("sizes", IMAGE_SIZES)
                }
                if (!ctx.forApp && !sources.kept) wrapInPicture(el, decodedSrc, ctx)
            } else if (isHttpUrl(decodedSrc)) {
                // the proxy refuses it (too long): never loaded from the author's host
                el.removeAttribute("src")
                el.removeAttribute("srcset")
                el.removeAttribute("sizes")
            }
        }
        // An author's srcset is kept only when every candidate is on the image proxy.
        val srcset = if (shouldReplace) el.getAttribute("srcset") else null
        if (!srcset.isNullOrEmpty() && srcset.split(',').any { !Js.trim(it).startsWith("$base/") }) {
            el.removeAttribute("srcset")
            el.removeAttribute("sizes")
        }
    }

    /** img.method.ts `bodyImageSources` result: the src and srcset the body gives an image. */
    class BodySources(val src: String, val srcSet: String, val kept: Boolean)

    /**
     * render-helper 2.5.58 `bodyImageSources` (#2191): the one rule for a markdown or HTML `<img>` and a linkified URL
     * or promoted link, or null when the proxy refuses it. A src already on the proxy (`/p/`, `/u/`, a sized route) is
     * kept as written, trimmed; a `/p/` one gets the sized srcset of its hash. Any other goes through `/p/`, unsized,
     * with a srcset of sized variants of the hash it got (a legacy host's `/p/` route too).
     */
    fun bodyImageSources(src: String, ctx: RenderContext): BodySources? {
        val base = Helper.trimTrailingSlash(ctx.proxy.base)
        val written = Js.trim(src)
        val kept = written.startsWith("$base/p/") || written.startsWith("$base/u/") || isSizedProxy(written, base)
        if (kept) return BodySources(written, if (written.startsWith("$base/p/")) ctx.proxy.srcSet(written) else "", true)
        val decoded = Helper.decodeDomImageSrc(src)
        val proxified = ctx.proxy.proxify(decoded, forceProxy = true)
        if (proxified.isEmpty()) return null
        return BodySources(proxified, ctx.proxy.srcSet(if (proxified.startsWith("$base/p/")) proxified else decoded), false)
    }

    /** 2.5.45: a `"` in a value (a configured proxy base) is written as `&quot;`. */
    private fun quoteAttr(v: String): String = v.replace("\"", "&quot;")

    fun createImageHTML(src: String, isLCP: Boolean, ctx: RenderContext): String {
        val decoded = Helper.decodeDomImageSrc(src)
        val sources = bodyImageSources(src, ctx) ?: return ""
        val proxified = sources.src
        val srcset = sources.srcSet
        val loading = if (isLCP) "eager" else "lazy"
        val fetch = if (isLCP) "fetchpriority=\"high\"" else "decoding=\"async\""
        val srcsetAttr = if (srcset.isNotEmpty()) "srcset=\"${quoteAttr(srcset)}\" sizes=\"$IMAGE_SIZES\"" else ""
        val imgTag = "<img\n    class=\"markdown-img-link\"\n    src=\"${quoteAttr(proxified)}\"\n    $srcsetAttr\n    loading=\"$loading\"\n" +
            "    $fetch\n    itemprop=\"image\"\n  />"
        if (!ctx.forApp) {
            val sources = ctx.proxy.pictureSources(decoded)
            if (sources != null) {
                return "<picture><source type=\"image/avif\" srcset=\"${quoteAttr(sources.first)}\" sizes=\"$IMAGE_SIZES\" />" +
                    "<source type=\"image/webp\" srcset=\"${quoteAttr(sources.second)}\" sizes=\"$IMAGE_SIZES\" />$imgTag</picture>"
            }
        }
        return imgTag
    }
}
