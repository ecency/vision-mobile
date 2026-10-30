package com.ecency.core.render.markdown

/**
 * Markdown to HTML, byte-identical to the Remarkable instance in @ecency/render-helper
 * (`markdown-to-html.method.ts`):
 *
 * ```js
 * new Remarkable({ html: true, breaks: true, typographer: false, highlight: (str) => str }).use(linkify)
 * md.core.ruler.enable(['abbr'])
 * md.block.ruler.enable(['footnote', 'deflist'])
 * md.inline.ruler.enable(['footnote_inline', 'ins', 'mark', 'sub', 'sup'])
 * ```
 *
 * A pure Kotlin port of remarkable 2.0.1 and the parts of Autolinker 3.16.2 its linkify plugin
 * uses. Golden tests in commonTest pin it to the JS output.
 */
object Markdown {
    private val inline = InlineParser()
    private val block = BlockParser()
    private val defaultInline = InlineParser(full = false)
    private val defaultBlock = BlockParser(full = false)

    /**
     * remarkable's `default` preset with `{ html: true, breaks: true, typographer: false }`, as
     * render-helper's `simpleMarkdownToHTML` ([linkify] true) and `postBodySummary` ([linkify]
     * false) use it: no abbr, deflist, ins, mark, sub, sup or footnote_inline.
     */
    fun renderDefault(src: String, linkify: Boolean): String {
        val env = Env()
        var tokens: MutableList<Token> = ArrayList()
        defaultBlock.parse(src, defaultInline, env, tokens)
        CoreRules.references(tokens, defaultInline, env)
        CoreRules.inline(tokens, defaultInline, env)
        tokens = CoreRules.footnoteTail(tokens, env)
        CoreRules.abbr2(tokens, env)
        if (linkify) Linkify.parseTokens(tokens)
        return Renderer.render(tokens, rawFences = false)
    }

    /**
     * Renders [src]. Throws where remarkable throws (for example `encodeURI` on a lone surrogate
     * inside a link destination); callers that render untrusted text should catch.
     */
    fun render(src: String): String = renderFull(src, linkify = true, rawFences = true)

    /**
     * The same rule set without linkify and with remarkable's default highlight (escaped fences):
     * render-helper's `postBodySummary` renderer.
     */
    fun renderSummary(src: String): String = renderFull(src, linkify = false, rawFences = false)

    private fun renderFull(src: String, linkify: Boolean, rawFences: Boolean): String {
        val env = Env()
        var tokens: MutableList<Token> = ArrayList()
        // core rules: block, abbr, references, inline, footnote_tail, abbr2, (replacements and
        // smartquotes are no-ops without typographer), linkify
        block.parse(src, inline, env, tokens)
        CoreRules.abbr(tokens, inline, env)
        CoreRules.references(tokens, inline, env)
        CoreRules.inline(tokens, inline, env)
        tokens = CoreRules.footnoteTail(tokens, env)
        CoreRules.abbr2(tokens, env)
        if (linkify) Linkify.parseTokens(tokens)
        return Renderer.render(tokens, rawFences)
    }
}
