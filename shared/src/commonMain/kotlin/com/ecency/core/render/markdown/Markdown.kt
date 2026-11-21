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
     * render-helper 2.5.46 full render: [ampEscapes] pairs escaped `&` in links ([AmpPairing]); destinations are
     * decoded once before linkify. Fenced code is escaped (the highlighter is not loaded, as in the goldens).
     */
    fun render(src: String, ampEscapes: Boolean): String = render(src, ampEscapes, { it }, null)

    /**
     * The full render with render-helper 2.5.53's rules (#2154): angle destinations kept undecoded ([Env.angleUnswap],
     * [unswap] puts the app render's placeholders back), and, when [codeUnswap] is given, code and fence tokens get
     * their entities back before linkify (`restore_code_entities`), exactly in the code the parser found.
     */
    internal fun render(
        src: String,
        ampEscapes: Boolean,
        unswap: (String) -> String,
        codeUnswap: ((String) -> String)?,
        linkifyView: ((String) -> LinkifyView?)? = null,
    ): String = renderFull(
        src, linkify = true, rawFences = false, full = true, ampEscapes = ampEscapes, unswap = unswap, codeUnswap = codeUnswap,
        linkifyView = linkifyView,
    )

    /**
     * The same rule set without linkify and with remarkable's default highlight (escaped fences):
     * render-helper's `postBodySummary` renderer.
     */
    fun renderSummary(src: String): String = renderFull(src, linkify = false, rawFences = false)

    private fun renderFull(
        src: String,
        linkify: Boolean,
        rawFences: Boolean,
        full: Boolean = false,
        ampEscapes: Boolean = false,
        unswap: ((String) -> String)? = null,
        codeUnswap: ((String) -> String)? = null,
        linkifyView: ((String) -> LinkifyView?)? = null,
    ): String {
        val env = Env()
        env.ampEscapes = ampEscapes
        env.angleUnswap = unswap
        var tokens: MutableList<Token> = ArrayList()
        // core rules: block, abbr, references, inline, footnote_tail, abbr2, (replacements and
        // smartquotes are no-ops without typographer), linkify
        block.parse(src, inline, env, tokens)
        CoreRules.abbr(tokens, inline, env)
        AmpPairing.references(tokens, inline, env)
        CoreRules.inline(tokens, inline, env)
        tokens = CoreRules.footnoteTail(tokens, env)
        CoreRules.abbr2(tokens, env)
        if (full) AmpPairing.decodeDestinations(tokens, env)
        if (codeUnswap != null) restoreCodeEntities(tokens, codeUnswap)
        if (linkify) {
            // 2.5.55 (#2160): on the app render linkify reads the text with its placeholders decoded
            if (linkifyView != null) DecodedLinkify.parseTokens(tokens, linkifyView) else Linkify.parseTokens(tokens)
        }
        val html = Renderer.render(tokens, rawFences)
        return if (ampEscapes) AmpPairing.sourceAmp(html) else html
    }

    /** render-helper 2.5.53 `restoreCodeEntities` (#2093, #2154): code renders its text verbatim, entities as written. */
    private fun restoreCodeEntities(tokens: List<Token>, unswap: (String) -> String) {
        fun restore(token: Token) {
            if (token.type != "code" && token.type != "fence") return
            if (token.content.isNotEmpty()) token.content = unswap(token.content)
            if (token.params.isNotEmpty()) token.params = unswap(token.params)
        }
        for (token in tokens) {
            restore(token)
            if (token.type == "inline") token.children.forEach(::restore)
        }
    }
}
