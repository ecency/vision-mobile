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
        val decodedSrc = Helper.decodeImageSrc(src)
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
        val hasAlreadyProxied = src.startsWith("$base/p/") || src.startsWith("$base/u/") || isSizedProxy(src, base)

        if (shouldReplace && !hasAlreadyProxied) {
            val proxified = ctx.proxy.proxify(decodedSrc, forceProxy = true)
            if (proxified.isNotEmpty()) {
                el.setAttribute("src", proxified)
                val srcset = ctx.proxy.srcSet(decodedSrc)
                if (srcset.isNotEmpty()) {
                    el.setAttribute("srcset", srcset)
                    el.setAttribute("sizes", IMAGE_SIZES)
                }
                if (!ctx.forApp) wrapInPicture(el, decodedSrc, ctx)
            }
        } else if (shouldReplace && hasAlreadyProxied) {
            if (src.startsWith("$base/p/")) {
                val srcset = ctx.proxy.srcSet(src)
                if (srcset.isNotEmpty()) {
                    el.setAttribute("srcset", srcset)
                    el.setAttribute("sizes", IMAGE_SIZES)
                }
            }
        }
    }

    fun createImageHTML(src: String, isLCP: Boolean, ctx: RenderContext): String {
        val decoded = Helper.decodeImageSrc(src)
        val proxified = ctx.proxy.proxify(decoded, forceProxy = true)
        if (proxified.isEmpty()) return ""
        val base = Helper.trimTrailingSlash(ctx.proxy.base)
        val isAlreadyProxied = decoded.startsWith("$base/u/") || isSizedProxy(decoded, base)
        val srcset = if (isAlreadyProxied) "" else ctx.proxy.srcSet(decoded)
        val loading = if (isLCP) "eager" else "lazy"
        val fetch = if (isLCP) "fetchpriority=\"high\"" else "decoding=\"async\""
        val srcsetAttr = if (srcset.isNotEmpty()) "srcset=\"$srcset\" sizes=\"$IMAGE_SIZES\"" else ""
        val imgTag = "<img\n    class=\"markdown-img-link\"\n    src=\"$proxified\"\n    $srcsetAttr\n    loading=\"$loading\"\n" +
            "    $fetch\n    itemprop=\"image\"\n  />"
        if (!ctx.forApp) {
            val sources = ctx.proxy.pictureSources(decoded)
            if (sources != null) {
                return "<picture><source type=\"image/avif\" srcset=\"${sources.first}\" sizes=\"$IMAGE_SIZES\" />" +
                    "<source type=\"image/webp\" srcset=\"${sources.second}\" sizes=\"$IMAGE_SIZES\" />$imgTag</picture>"
            }
        }
        return imgTag
    }
}
