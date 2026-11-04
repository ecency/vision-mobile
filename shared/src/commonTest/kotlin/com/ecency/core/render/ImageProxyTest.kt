package com.ecency.core.render

import com.ecency.core.render.url.WebUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageProxyTest {
    @Test
    fun matchesRenderHelper() {
        val failures = mutableListOf<String>()
        for (c in ProxyFixtures.cases) {
            val proxy = ImageProxy(c.base)
            ProxyFixtures.SIZES.forEachIndexed { i, (w, h, blur, force) ->
                val actual = proxy.proxify(c.url, w, h, blur = blur == 1, forceProxy = force == 1)
                if (actual != c.proxied[i]) failures += "${c.base} ${c.url.take(80)} size#$i\n  expected ${c.proxied[i]}\n  actual   $actual"
            }
            fun check(name: String, expected: Any, actual: Any) {
                if (expected != actual) failures += "${c.base} ${c.url.take(80)} $name\n  expected $expected\n  actual   $actual"
            }
            check("srcSet", c.srcSet, proxy.srcSet(c.url))
            check("avifSrcSet", c.avifSrcSet, proxy.srcSetForFormat(c.url, "avif"))
            check("pictureEligible", c.pictureEligible, proxy.isPictureEligibleRawUrl(c.url))
            check("legacySized", c.legacySized, ImageProxy.isLegacySizedProxyUrl(c.url))
        }
        assertTrue(failures.isEmpty(), "${failures.size} mismatches:\n" + failures.joinToString("\n"))
    }

    /**
     * A lone surrogate in a URL (any string can carry one, e.g. from a JSON `\uD83D` escape) is
     * UTF-8 encoded as U+FFFD by JS, as on Kotlin/Native; Kotlin/JVM's encoder writes `?`
     * instead. Expected values from render-helper 2.5.42 and Node's URL.
     */
    @Test
    fun loneSurrogatesEncodeAsReplacementCharacter() {
        val proxy = ImageProxy("https://images.ecency.com")
        assertEquals(
            "https://images.ecency.com/p/F7pXcna7voXvYrDYhJxLFxgTNiembRj6oZVup?format=match&mode=fit",
            proxy.proxify("https://example.com/\uD83D.png", forceProxy = true),
        )
        assertEquals(
            "https://images.ecency.com/p/82kPqomaPXmNY7iuk9uvAaMw7r3WScja44Zda5uDHTiv?format=match&mode=fit",
            proxy.proxify("https://example.com/a\uDC1D\uD83D\uDC1D.png", forceProxy = true),
        )
        assertEquals("https://example.com/%EF%BF%BD.png", WebUrl.parse("https://example.com/\uD83D.png")?.href)
        assertEquals("https://example.com/?q=%EF%BF%BD#%EF%BF%BD", WebUrl.parse("https://example.com/?q=\uD83D#\uDC1D")?.href)
    }
}
