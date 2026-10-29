package com.ecency.core.render.methods

import com.ecency.core.render.ImageProxy
import com.ecency.core.render.RenderOptions
import com.ecency.core.render.SeoContext
import com.ecency.core.render.sanitize.SanitizeOptions

/**
 * Everything one render reads that render-helper keeps in arguments or module globals
 * (`setProxyBase`). Immutable, so concurrent renders never share mutable state.
 */
internal class RenderContext(
    val forApp: Boolean,
    val parentDomain: String,
    val seo: SeoContext?,
    val options: RenderOptions,
    val proxy: ImageProxy,
) {
    val sanitizeOptions = SanitizeOptions(preserveImageDimensions = options.preserveImageDimensions, proxyBase = proxy.base)
}

/** traverse's `{ firstImageFound, imageCount }`, one per document pass. */
internal class ImageState {
    var firstImageFound = false
    var imageCount: Int? = null
}

/** A JS TypeError the transforms would raise; markdownToHTML treats it like any other throw. */
internal class TransformError(message: String) : RuntimeException(message)

/**
 * Errors a render must never swallow: out of memory, and coroutine cancellation (a render on a
 * cancelled background job must stop, not fall back).
 */
internal fun isFatal(t: Throwable): Boolean = t is OutOfMemoryError || t is kotlin.coroutines.cancellation.CancellationException
