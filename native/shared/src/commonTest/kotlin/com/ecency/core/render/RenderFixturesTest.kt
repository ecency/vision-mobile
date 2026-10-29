package com.ecency.core.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The original sample set (native/tools/render/generate-fixtures.mjs) through the public API. */
class RenderFixturesTest {
    private val helper = RenderHelper(RenderFixtures.IMAGE_PROXY)

    @Test
    fun samplesMatchRenderHelper() {
        for (s in RenderFixtures.samples) assertEquals(s.html, helper.renderPostBody(s.markdown, true), s.name)
    }

    @Test
    fun postMatchesRenderHelper() {
        val p = Json.parseToJsonElement(RenderFixtures.POST_JSON).jsonObject
        fun f(k: String) = p[k]?.jsonPrimitive?.content
        val entry = Entry(f("author"), f("permlink"), f("last_update"), f("body")!!, f("json_metadata"))
        assertEquals(RenderFixtures.POST_HTML, helper.renderPostBody(entry, true))
        assertEquals(RenderFixtures.POST_SUMMARY, helper.postBodySummary(entry, 120))
        assertEquals(RenderFixtures.POST_IMAGE, helper.catchPostImage(entry) ?: "")
    }
}
