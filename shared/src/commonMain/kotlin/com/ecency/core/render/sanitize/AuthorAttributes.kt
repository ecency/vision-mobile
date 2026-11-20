package com.ecency.core.render.sanitize

import com.ecency.core.render.js.Js

/** render-helper 2.5.45 `consts/allowed-attributes.const.ts` additions (#1919): what an author may write. */
object AuthorAttributes {
    /** data-* attributes the DOM transforms set to mark links they built; author-written copies are stripped. */
    val RENDER_HELPER_DATA_ATTRIBUTES: Set<String> = setOf(
        "data-author", "data-community", "data-embed-src", "data-filter", "data-href", "data-is-inline", "data-permlink",
        "data-proposal", "data-start-time", "data-tag", "data-video-href", "data-youtube",
    )

    /** The only sandbox tokens iframe() and a() ever grant an embed. */
    val IFRAME_SANDBOX_GRANTS: Set<String> = setOf("allow-scripts", "allow-same-origin", "allow-popups")

    const val AUTHOR_ID_PREFIX = "user-content-"

    /** An author-written id, namespaced away from the page's ids; applied once per render. */
    fun namespacedId(id: String): String = AUTHOR_ID_PREFIX + id

    private val AUTHOR_CLASSES = setOf(
        "pull-right", "pull-left", "text-justify", "text-rtl", "text-center", "text-right", "videoWrapper", "phishy",
        "twitter-tweet",
    )
    private val MARKDOWN_STAGE_CLASSES = setOf(
        "footnote-backref", "footnote-item", "footnote-ref", "footnotes", "footnotes-list", "footnotes-sep",
    )

    private fun hasMarkdownDash(token: String) = Js.toLowerCase(token).contains("markdown-")

    /** `/^ecency-text-color-[0-9a-f]{6}$/`: the editor's text colour classes. */
    private fun isTextColor(t: String): Boolean {
        val p = "ecency-text-color-"
        return t.length == p.length + 6 && t.startsWith(p) && t.substring(p.length).all { it in '0'..'9' || it in 'a'..'f' }
    }

    /** `/^(?:ll-[a-z]+|language-[A-Za-z0-9_+#.-]+)$/`: code highlighting and a fenced block's language. */
    private fun isMarkdownStageClass(t: String): Boolean = when {
        t.startsWith("ll-") -> t.length > 3 && t.substring(3).all { it in 'a'..'z' }
        t.startsWith("language-") -> t.length > 9 &&
            t.substring(9).all { Js.isAsciiAlpha(it) || Js.isDigit(it) || it in "_+#.-" }
        else -> false
    }

    private fun isControl(c: Char) = c.code <= 0x1f || c.code == 0x7f

    /** `authorClasses`: a class value reduced to the classes an author may use; unchanged when all are kept. */
    fun authorClasses(value: String): String {
        val tokens = HtmlSanitizer.splitWs(value.map { if (isControl(it)) ' ' else it }.joinToString(""))
        val kept = tokens.filter { t ->
            !hasMarkdownDash(t) && (t in AUTHOR_CLASSES || isTextColor(t) || t in MARKDOWN_STAGE_CLASSES || isMarkdownStageClass(t))
        }
        return if (kept.size == tokens.size && value.none(::isControl)) value else kept.joinToString(" ")
    }

    /** `stripMarkdownClasses`: a class value without render-helper's `markdown-*` marker tokens. */
    fun stripMarkdownClasses(value: String): String {
        val tokens = HtmlSanitizer.splitWs(value)
        val kept = tokens.filter { !hasMarkdownDash(it) }
        return if (kept.size == tokens.size) value else kept.joinToString(" ")
    }
}
