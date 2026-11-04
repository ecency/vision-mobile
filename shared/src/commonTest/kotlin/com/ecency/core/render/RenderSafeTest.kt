package com.ecency.core.render

import kotlin.test.Test
import kotlin.test.assertEquals

class RenderSafeTest {
    private val helper = RenderHelper("https://images.ecency.com")

    @Test
    fun normalBodiesRenderAsRenderPostBody() {
        for (body in listOf("Hello **world** @example-user #tag", "![](https://example.com/a.png)", "")) {
            assertEquals(helper.renderPostBody(body), helper.renderPostBodySafe(body))
        }
    }

    @Test
    fun blankResultForAContentBodyFallsBackToEscapedText() {
        // The sanitizer drops a style-only body entirely.
        val body = "<style>p{color:\"red\"}</style>\n<style>a & b</style>"
        kotlin.test.assertTrue(helper.renderPostBody(body).isBlank())
        assertEquals("<p>&lt;style&gt;p{color:&quot;red&quot;}&lt;/style&gt;<br>&lt;style&gt;a &amp; b&lt;/style&gt;</p>", helper.renderPostBodySafe(body))
    }

    @Test
    fun bodiesThatAreOnlyABylineStayEmpty() {
        assertEquals("", helper.renderPostBodySafe("Posted using [Partiko Android](https://partiko.app/x)"))
    }
}
