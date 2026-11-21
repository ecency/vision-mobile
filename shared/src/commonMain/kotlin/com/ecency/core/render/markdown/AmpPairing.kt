package com.ecency.core.render.markdown

import com.ecency.core.render.methods.Helper

/**
 * render-helper 2.5.46 (#2092, #2098) in `markdown-to-html.method.ts`: a backslash-escaped `&` in a link or image
 * destination is a literal ampersand (CommonMark), but Remarkable drops the backslash before the destinations are
 * decoded. So while Remarkable parses a link, an image or a reference definition, and only then, each escaped `&` is
 * swapped for [PAIR] (same length, so every position holds); the destinations get a literal `&` back after their
 * references are decoded ([decodeDestinations]). Off ([Env.ampEscapes]) for a body that already holds the pair.
 */
internal object AmpPairing {
    const val PAIR = ""
    private const val PAIR_ENCODED = "%EE%80%80%EE%80%81"

    /** The source with each escaped `&` (an odd run of backslashes before it) as [PAIR], or null when it has none. */
    fun pairedSource(src: String): String? {
        if (src.indexOf("\\&") < 0) return null
        val sb = StringBuilder(src.length)
        var i = 0
        var any = false
        while (i < src.length) {
            if (src[i] != '\\') {
                sb.append(src[i++])
                continue
            }
            val start = i
            while (i < src.length && src[i] == '\\') i++
            val run = i - start
            if (i < src.length && src[i] == '&' && run % 2 == 1) {
                sb.append(src, start, i - 1).append(PAIR)
                i++
                any = true
            } else {
                sb.append(src, start, i)
                if (i < src.length && src[i] == '&') sb.append(src[i++])
            }
        }
        return if (any) sb.toString() else null
    }

    fun hrefAmp(v: String): String = v.replace(PAIR, "&").replace(PAIR_ENCODED, "&")
    fun literalAmp(v: String): String = v.replace(PAIR, "&")
    fun sourceAmp(v: String): String = v.replace(PAIR, "\\&")

    /** `withPairedSource`: the `links` rule parses the paired source. */
    fun links(state: StateInline, silent: Boolean): Boolean {
        if (!state.env.ampEscapes || state.ampInside) return InlineRules.links(state, silent)
        if (!state.ampPairedDone) {
            state.ampPaired = state.pairedSource()
            state.ampPairedDone = true
        }
        val paired = state.ampPaired ?: return InlineRules.links(state, silent)
        val src = state.src
        state.src = paired
        state.ampInside = true
        state.ampOriginal = src
        try {
            return InlineRules.links(state, silent)
        } finally {
            state.src = src
            state.ampInside = false
            state.ampOriginal = null
        }
    }

    /** `withOriginalSource`: an autolink inside a label reads the original source (an autolink takes no escapes). */
    fun autolink(state: StateInline, silent: Boolean): Boolean {
        val original = state.ampOriginal
        if (!state.ampInside || original == null) return InlineRules.autolink(state, silent)
        val paired = state.src
        state.src = original
        try {
            return InlineRules.autolink(state, silent)
        } finally {
            state.src = paired
        }
    }

    /** `withPairedReferences`: reference definitions are parsed from paired paragraph content. */
    fun references(tokens: List<Token>, inline: InlineParser, env: Env) {
        if (!env.ampEscapes) return CoreRules.references(tokens, inline, env)
        val originals = ArrayList<Triple<Token, String, String>>()
        for (token in tokens) {
            if (token.type != "inline" || token.content.isEmpty()) continue
            val paired = pairedSource(token.content) ?: continue
            originals.add(Triple(token, token.content, paired))
            token.content = paired
        }
        try {
            CoreRules.references(tokens, inline, env)
        } finally {
            for ((token, original, paired) in originals) {
                token.content = if (token.content == paired) original else token.content.replace(PAIR, "\\&")
            }
        }
    }

    /**
     * `decodeDestinations` (core rule before linkify): a markdown image or link destination's complete references
     * decoded once (#2092), then, on a paired render, the pair back to what the source said.
     */
    fun decodeDestinations(tokens: List<Token>, env: Env) {
        for (block in tokens) {
            if (block.type != "inline") continue
            for (token in block.children) {
                if (token.type == "image" && token.src.isNotEmpty()) token.src = Helper.decodeCompleteReferences(token.src)
                else if (token.type == "link_open" && token.href.isNotEmpty()) token.href = Helper.decodeCompleteReferences(token.href)
                if (!env.ampEscapes) continue
                if (token.src.isNotEmpty()) token.src = hrefAmp(token.src)
                if (token.href.isNotEmpty()) token.href = hrefAmp(token.href)
                if (token.alt.isNotEmpty()) token.alt = literalAmp(token.alt)
                token.title?.let { if (it.isNotEmpty()) token.title = literalAmp(it) }
                if (token.content.indexOf(PAIR) >= 0) {
                    token.content = if (token.type == "code" || token.type == "htmltag") sourceAmp(token.content) else literalAmp(token.content)
                }
            }
        }
    }
}
