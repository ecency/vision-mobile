package com.ecency.core.render

import kotlin.test.Test
import kotlin.test.assertEquals

/** Body images load the srcset entry a phone browser picks on web, so they hit the same CDN cache entry. */
class BodyImageVariantTest {
    private val proxy = ImageProxy()

    private fun entries(srcSet: String): Map<Int, String> =
        srcSet.split(", ").associate { c -> c.substringAfterLast(' ').removeSuffix("w").toInt() to c.substringBeforeLast(' ') }

    /** render-helper's `<img src>` for a raw body image. */
    private fun src(raw: String) = proxy.proxify(raw, forceProxy = true)

    @Test
    fun staticRasterLoadsWebsPictureSourceAtTheSmallestWidthThatCovers() {
        val raw = "https://files.peakd.com/file/peakd-hive/alice/photo.jpg"
        val (avifSet, webpSet) = proxy.pictureSources(raw)!!
        val avif = entries(avifSet)
        val webp = entries(webpSet)
        val cases = mapOf(1 to 320, 320 to 320, 321 to 600, 799 to 800, 800 to 800, 1000 to 1024, 1280 to 1280, 1300 to 1280, 4000 to 1280)
        for ((px, w) in cases) {
            assertEquals(avif.getValue(w), ImageProxy.bodyVariant(src(raw), px, avif = true), "avif at $px px")
            assertEquals(webp.getValue(w), ImageProxy.bodyVariant(src(raw), px, avif = false), "webp at $px px")
        }
        val hash = src(raw).substringAfter("/p/").substringBefore('?')
        assertEquals("https://i.ecency.com/p/$hash?format=avif&mode=fit&width=800", ImageProxy.bodyVariant(src(raw), 799, avif = true))
        assertEquals("https://i.ecency.com/p/$hash?format=webp&mode=fit&width=1280", ImageProxy.bodyVariant(src(raw), 1300, avif = false))
    }

    /** Web has no `<picture>` for gifs (animated) or files without a raster extension: their `<img>` srcset is format=match. */
    @Test
    fun animatedAndExtensionlessSourcesKeepWebsMatchSrcset() {
        for (raw in listOf("https://media.giphy.com/media/abc/giphy.gif", "https://files.peakd.com/file/peakd-hive/alice/AbCdEf")) {
            val match = entries(proxy.srcSet(raw))
            for ((px, w) in mapOf(320 to 320, 799 to 800, 1300 to 1280)) {
                assertEquals(match.getValue(w), ImageProxy.bodyVariant(src(raw), px, avif = true), "$raw at $px px")
                assertEquals(match.getValue(w), ImageProxy.bodyVariant(src(raw), px, avif = false), "$raw at $px px")
            }
            assertEquals(true, ImageProxy.bodyVariant(src(raw), 800, avif = true).endsWith("?format=match&mode=fit&width=800"))
        }
    }

    /** An author's own /p/ URL is kept by render-helper with a format=match srcset over its hash; web has no `<picture>`. */
    @Test
    fun alreadyProxiedSourceUsesTheMatchSrcsetOfItsHash() {
        val pasted = "https://i.ecency.com/p/2bP4pJr4wVimqCWjYimXJe2cnCgnM7aPAGpC7PAdBqT?format=match&mode=fit&width=200"
        assertEquals(
            "https://i.ecency.com/p/2bP4pJr4wVimqCWjYimXJe2cnCgnM7aPAGpC7PAdBqT?format=match&mode=fit&width=600",
            ImageProxy.bodyVariant(pasted, 500, avif = true),
        )
    }

    @Test
    fun otherHostsAndRoutesStayAsTheyAre() {
        val untouched = listOf(
            "https://example.com/a.jpg",
            "https://i.ecency.com/u/alice/avatar/large",
            "https://i.ecency.com/0x0/https://example.com/a.jpg",
            "https://i.ecency.com/DQmSomeHash/a.jpg",
            ImageProxy("https://images.hive.blog").proxify("https://example.com/a.jpg", forceProxy = true),
        )
        for (u in untouched) assertEquals(u, ImageProxy.bodyVariant(u, 800, avif = true))
    }

    @Test
    fun noCdnServerGetsTheSameSizes() {
        val raw = "https://files.peakd.com/file/peakd-hive/alice/photo.png"
        val nocdn = ImageProxy("https://img.ecency.com")
        val expected = entries(nocdn.pictureSources(raw)!!.first).getValue(1024)
        assertEquals(expected, ImageProxy.bodyVariant(nocdn.proxify(raw, forceProxy = true), 1000, avif = true))
    }
}
