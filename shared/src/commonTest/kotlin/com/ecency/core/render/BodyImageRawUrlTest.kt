package com.ecency.core.render

import com.ecency.core.render.transform.RenderCases
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `getEntryImageRawUrl` on a body: render-helper 2.5.52's `bodyImageRawUrl` decodes each kind of candidate its own
 * way (an `<img src>` as an attribute, a markdown destination with its escapes, a bare URL with trailing punctuation
 * trimmed, a bare URL on an HTML block line as HTML text). Expected values are render-helper 2.5.52's output on the
 * same bodies (review of #3714).
 */
class BodyImageRawUrlTest {
    private val helper = RenderHelper(RenderCases.PROXY_BASE)

    private val cases = listOf(
        "see https://example.com/a.jpg?x=1." to "https://example.com/a.jpg?x=1",
        "see https://example.com/a.jpg?x=1!?" to "https://example.com/a.jpg?x=1",
        "see https://example.com/a.jpg?x=1)." to "https://example.com/a.jpg?x=1",
        "<a href=\"x\">https://example.com/a.jpg?x=1.</a>" to null,
        "see https://example.com/a.jpg?x=1&amp;" to "https://example.com/a.jpg?x=1&",
        "see https://example.com/a.jpg?x=1&amp;." to "https://example.com/a.jpg?x=1&",
        "see https://e.com/a.jpg?x=1\\&amp;y=2" to "https://e.com/a.jpg?x=1&amp;y=2",
        "see https://e.com/a.jpg?x=1\\&y=2" to "https://e.com/a.jpg?x=1&y=2",
        "![x](https://e.com/a.jpg?x=1\\&y=2\\_z)" to "https://e.com/a.jpg?x=1&y=2_z",
        "![x](https://e.com/a.jpg?x=1&copy;&amp;)" to "https://e.com/a.jpg?x=1©&",
        "[https://e.com/a.jpg?x=1&amp;y](https://e.com/a.jpg?x=1&amp;y)" to "https://e.com/a.jpg?x=1&y",
        "<img src=\"https://e.com/a.jpg?x=1&times=2&amp;y\">" to "https://e.com/a.jpg?x=1&times=2&y",
        "<div>\nhttps://example.com/a.jpg?x=1&amp;y=2&copy\n</div>" to "https://example.com/a.jpg?x=1&y=2©",
        "<div>\nhttps://example.com/a.jpg?x=1&#38;y=2.\n</div>" to "https://example.com/a.jpg?x=1&y=2.",
        "<center>\n\nhttps://example.com/a%20b.jpg?x=1&lt;b\n\n</center>" to "https://example.com/a b.jpg?x=1",
        "https://example.com/a.jpg?x=1&amp;y=2&GT;z" to "https://example.com/a.jpg?x=1&y=2",
        "`https://code.example/a.jpg` then https://example.com/b.png?x=1:" to null,
    )

    @Test
    fun eachCandidateKindIsDecodedAsRenderHelperDecodesIt() {
        val wrong = cases.filter { (body, expected) -> helper.getEntryImageRawUrl(body) != expected }
            .map { (body, expected) -> "$body: expected $expected, got ${helper.getEntryImageRawUrl(body)}" }
        assertEquals(emptyList(), wrong)
    }
}
