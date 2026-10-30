package com.ecency.core.render

import com.ecency.core.render.methods.CleanReply
import com.ecency.core.render.methods.MarkdownToHtml
import com.ecency.core.render.methods.RenderContext
import com.ecency.core.render.methods.isFatal
import com.ecency.core.render.markdown.Markdown
import com.ecency.core.render.sanitize.HtmlSanitizer
import com.ecency.core.render.sanitize.SanitizeOptions

/**
 * Pure Kotlin port of `@ecency/render-helper` 2.5.42, golden-tested against the JS package.
 *
 * Threading: renders are CPU-bound and can take seconds on pathological bodies, so they must
 * run off the main thread (a background dispatcher or queue).
 *
 * Stateless: the image proxy base is a constructor argument (the JS `setProxyBase` global), there
 * is no render cache and no shared mutable state, so one instance can be used from any number of
 * threads at once. Inputs are untrusted post bodies; like the JS functions, these can throw only
 * where render-helper itself throws (a markdown body remarkable cannot render even in the fallback).
 */
class RenderHelper(proxyBase: String = ImageProxy.DEFAULT_BASE) {
    val proxy = ImageProxy(proxyBase)

    /**
     * `renderPostBody(body, forApp, _webp, parentDomain, seoContext, renderOptions)` for a raw body
     * (the JS string overload: `cleanReply` then `markdownToHTML`).
     */
    fun renderPostBody(
        body: String,
        forApp: Boolean = true,
        parentDomain: String = "ecency.com",
        seoContext: SeoContext? = null,
        options: RenderOptions = RenderOptions(),
    ): String = MarkdownToHtml.markdownToHTML(
        CleanReply.cleanReply(body),
        RenderContext(forApp, parentDomain, seoContext, options, proxy),
    )

    /**
     * App-facing [renderPostBody]: never throws on post content. Any failure (other than out of
     * memory or cancellation), or an empty result for a body that still has content after the
     * byline cleanup, falls back to the body as escaped plain text with line breaks, so a post is
     * never shown blank. Like every render, call it off the main thread: a pathological body can
     * take seconds.
     */
    fun renderPostBodySafe(
        body: String,
        forApp: Boolean = true,
        parentDomain: String = "ecency.com",
        seoContext: SeoContext? = null,
        options: RenderOptions = RenderOptions(),
    ): String {
        val out = try {
            renderPostBody(body, forApp, parentDomain, seoContext, options)
        } catch (t: Throwable) {
            if (isFatal(t)) throw t
            null
        }
        if (out != null && (out.isNotBlank() || CleanReply.cleanReply(body).isBlank())) return out
        return plainText(body)
    }

    private fun plainText(body: String): String {
        val sb = StringBuilder(body.length + 16)
        for (c in body) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\n' -> sb.append("<br>")
                '\r' -> {}
                else -> sb.append(c)
            }
        }
        return "<p>$sb</p>"
    }

    /** The Entry overload: renders [Entry.body] (render-helper's per-entry cache is not ported). */
    fun renderPostBody(
        entry: Entry,
        forApp: Boolean = true,
        parentDomain: String = "ecency.com",
        seoContext: SeoContext? = null,
        options: RenderOptions = RenderOptions(),
    ): String = renderPostBody(entry.body, forApp, parentDomain, seoContext, options)

    /** `simpleMarkdownToHTML`: remarkable's default preset plus linkify, then the sanitizer. */
    fun simpleMarkdownToHTML(input: String): String {
        if (input.isEmpty()) return ""
        return HtmlSanitizer.sanitize(Markdown.renderDefault(input, linkify = true), SanitizeOptions(proxyBase = proxy.base))
    }

    /** `postBodySummary(body, length, platform)`: plain-text summary; [length] 0 means no truncation. */
    fun postBodySummary(body: String, length: Int = 200, platform: Platform = Platform.WEB): String =
        PostBodySummary.summary(body, length, platform)

    fun postBodySummary(entry: Entry, length: Int = 200, platform: Platform = Platform.WEB): String =
        postBodySummary(entry.body, length, platform)

    /** `catchPostImage(entry, width, height, 'match', { fast })`: the proxied card image, or null. */
    fun catchPostImage(entry: Entry, width: Int = 0, height: Int = 0, fast: Boolean = false): String? =
        CatchPostImage(this).forEntry(entry, width, height, fast)

    /** The string overload of `catchPostImage` (no metadata). */
    fun catchPostImage(body: String, width: Int = 0, height: Int = 0, fast: Boolean = false): String? =
        CatchPostImage(this).forBody(body, width, height, fast)

    /** `getEntryImageRawUrl`: the raw URL of the first image the body renders (metadata image first). */
    fun getEntryImageRawUrl(entry: Entry): String? = CatchPostImage(this).entryImageRawUrl(entry)

    fun getEntryImageRawUrl(body: String): String? = CatchPostImage(this).bodyImageRawUrl(body)

    /** `getEntryCardImageRawUrl`: thumbnails, then metadata image, then the body. */
    fun getEntryCardImageRawUrl(entry: Entry): String? = CatchPostImage(this).cardImageRawUrl(entry)

    enum class Platform { WEB, IOS, ANDROID }
}
