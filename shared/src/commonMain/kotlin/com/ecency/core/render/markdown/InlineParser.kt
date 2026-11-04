package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
internal typealias InlineRule = (state: StateInline, silent: Boolean) -> Boolean

/** remarkable's StateInline. `pending` is a builder; JS concatenates strings. */
internal class StateInline(
    val src: String,
    val parser: InlineParser,
    val env: Env,
    val tokens: MutableList<Token>,
) {
    var pos = 0
    var posMax = src.length
    var level = 0
    val pending = StringBuilder()
    var pendingLevel = 0
    private var cache = IntArray(0)
    private var cacheSize = 0
    var isInLabel = false
    var linkLevel = 0
    var linkContent = ""
    var labelUnmatchedScopes = 0

    /** Last index of '>' in [src]; `src.indexOf('>', pos) < 0` is `pos > lastGt` without a scan. */
    val lastGt: Int by lazy { src.lastIndexOf('>') }

    fun pushPending() {
        tokens.add(Token("text", pendingLevel, pending.toString()))
        pending.setLength(0)
    }

    fun push(token: Token) {
        if (pending.isNotEmpty()) pushPending()
        tokens.add(token)
        pendingLevel = level
    }

    fun cacheSet(key: Int, value: Int) {
        if (key >= cache.size) cache = cache.copyOf(maxOf(key + 1, cache.size * 2, 16))
        if (key >= cacheSize) cacheSize = key + 1
        cache[key] = value
    }

    fun cacheGet(key: Int): Int = if (key < cacheSize) cache[key] else 0
}

/** remarkable's ParserInline with every inline rule enabled, as render-helper configures it. */
internal class InlineParser private constructor(
    private val rules: List<InlineRule>,
    /** One more character `_` emphasis counts as part of a word (chat's link and mention placeholder), or -1. */
    val extraWordChar: Int = -1,
) {
    // `full` adds ins, mark, sub, sup and footnote_inline (render-helper); false is the default preset.
    constructor(full: Boolean = true) : this(presetRules(full))

    companion object {
        /**
         * Chat's inline subset (README "Chats"): the default preset's text, escape, code span, strikethrough and
         * emphasis rules alone, so `**`, `*`, `_`, `~~` and backticks read exactly as `simpleMarkdownToHTML` reads
         * them while links, autolinks, HTML (`<user>` stays text), entities and line breaks stay as typed.
         */
        fun chat(placeholder: Char = '\uFFFC'): InlineParser = InlineParser(
            listOf(InlineRules::text, InlineRules::escape, InlineRules::backticks, InlineRules::del, InlineRules::emphasis),
            placeholder.code,
        )

        private fun presetRules(full: Boolean): List<InlineRule> = if (full) {
            listOf(
                InlineRules::text, InlineRules::newline, InlineRules::escape, InlineRules::backticks,
                InlineRules::del, InlineRules::ins, InlineRules::mark, InlineRules::emphasis, InlineRules::sub,
                InlineRules::sup, InlineRules::links, InlineRules::footnoteInline, InlineRules::footnoteRef,
                InlineRules::autolink, InlineRules::htmltag, InlineRules::entity,
            )
        } else {
            listOf(
                InlineRules::text, InlineRules::newline, InlineRules::escape, InlineRules::backticks,
                InlineRules::del, InlineRules::emphasis, InlineRules::links, InlineRules::footnoteRef,
                InlineRules::autolink, InlineRules::htmltag, InlineRules::entity,
            )
        }
    }

    fun skipToken(state: StateInline) {
        val pos = state.pos
        val cachedPos = state.cacheGet(pos)
        if (cachedPos > 0) {
            state.pos = cachedPos
            return
        }
        for (rule in rules) {
            if (rule(state, true)) {
                state.cacheSet(pos, state.pos)
                return
            }
        }
        state.pos++
        state.cacheSet(pos, state.pos)
    }

    fun tokenize(state: StateInline) {
        val end = state.posMax
        while (state.pos < end) {
            var ok = false
            for (rule in rules) {
                ok = rule(state, false)
                if (ok) break
            }
            if (ok) {
                if (state.pos >= end) break
                continue
            }
            state.pending.append(state.src[state.pos++])
        }
        if (state.pending.isNotEmpty()) state.pushPending()
    }

    fun parse(str: String, env: Env, outTokens: MutableList<Token>) {
        tokenize(StateInline(str, this, env, outTokens))
    }

    /** Link label scanner shared by links, footnotes, references and abbreviations. */
    fun parseLinkLabel(state: StateInline, start: Int): Int {
        var labelEnd = -1
        val max = state.posMax
        val oldPos = state.pos
        val oldFlag = state.isInLabel
        if (state.isInLabel) return -1
        if (state.labelUnmatchedScopes != 0) {
            state.labelUnmatchedScopes--
            return -1
        }
        state.pos = start + 1
        state.isInLabel = true
        var level = 1
        var found = false
        while (state.pos < max) {
            val marker = Js.cc(state.src, state.pos)
            if (marker == 0x5B) {
                level++
            } else if (marker == 0x5D) {
                level--
                if (level == 0) {
                    found = true
                    break
                }
            }
            skipToken(state)
        }
        if (found) {
            labelEnd = state.pos
            state.labelUnmatchedScopes = 0
        } else {
            state.labelUnmatchedScopes = level - 1
        }
        state.pos = oldPos
        state.isInLabel = oldFlag
        return labelEnd
    }
}
