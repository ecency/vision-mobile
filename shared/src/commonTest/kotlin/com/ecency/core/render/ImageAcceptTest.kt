package com.ecency.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageAcceptTest {
    @Test
    fun theProxyHostsGetTheFormatsTheAppDecodes() {
        for (url in listOf(
            "https://i.ecency.com/p/abc?format=match&mode=fit",
            "https://images.ecency.com/u/someone/avatar/small",
            "https://img.ecency.com/u/someone/cover",
            "https://I.Ecency.COM/p/abc",
            "https://i.ecency.com",
        )) {
            assertEquals("image/avif,image/webp,*/*", ImageAccept.header(url, avif = true, webp = true), url)
            assertEquals("image/webp,*/*", ImageAccept.header(url, avif = false, webp = true), url)
            assertNull(ImageAccept.header(url, avif = false, webp = false), url)
        }
    }

    /** The CDN groups a client only by bare list items: no spaces, no parameters, lower case. */
    @Test
    fun theHeaderIsABareList() {
        for (header in listOf(ImageAccept.header("https://i.ecency.com/p/a", true, true)!!, ImageAccept.header("https://i.ecency.com/p/a", false, true)!!)) {
            assertEquals(header.lowercase(), header)
            for (item in header.split(',')) assertEquals(item.trim().substringBefore(';'), item)
        }
    }

    @Test
    fun otherHostsAreLeftAlone() {
        for (url in listOf(
            "https://images.hive.blog/p/abc",
            "https://files.peakd.com/file/a.png",
            "https://i.ecency.com.example.org/p/abc",
            "https://example.org/i.ecency.com/p/abc",
            "https://user@i.ecency.com/p/abc",
            "http://i.ecency.com/p/abc",
            "https://i.ecency.com:8443/p/abc",
            "i.ecency.com/p/abc",
            "",
        )) {
            assertNull(ImageAccept.header(url, avif = true, webp = true), url)
        }
    }
}
