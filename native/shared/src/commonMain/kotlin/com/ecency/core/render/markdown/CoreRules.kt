package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
/** remarkable's core rules (lib/rules_core) that run with render-helper's options, in order. */
internal object CoreRules {

    private fun forEachParagraphInline(tokens: List<Token>, action: (prev: Token, tok: Token, next: Token) -> Unit) {
        var i = 1
        val l = tokens.size - 1
        while (i < l) {
            if (tokens[i - 1].type == "paragraph_open" && tokens[i].type == "inline" && tokens[i + 1].type == "paragraph_close") {
                action(tokens[i - 1], tokens[i], tokens[i + 1])
            }
            i++
        }
    }

    // ---- abbr: `*[label]: title` definitions --------------------------------------------------

    private fun parseAbbr(str: String, inline: InlineParser, env: Env): Int {
        if (Js.cc(str, 0) != 0x2A) return -1
        if (Js.cc(str, 1) != 0x5B) return -1
        if (str.indexOf("]:") == -1) return -1
        val state = StateInline(str, inline, env, ArrayList())
        val labelEnd = inline.parseLinkLabel(state, 1)
        if (labelEnd < 0 || Js.cc(str, labelEnd + 1) != 0x3A) return -1
        val max = state.posMax
        var pos = labelEnd + 2
        while (pos < max) {
            if (Js.cc(state.src, pos) == 0x0A) break
            pos++
        }
        val label = Js.slice(str, 2, labelEnd)
        val title = Js.trim(Js.slice(str, labelEnd + 2, pos))
        if (title.isEmpty()) return -1
        val abbreviations = env.abbreviations ?: LinkedHashMap<String, String>().also { env.abbreviations = it }
        if (!abbreviations.containsKey(label)) abbreviations[label] = title
        return pos
    }

    fun abbr(tokens: List<Token>, inline: InlineParser, env: Env) {
        forEachParagraphInline(tokens) { open, tok, close ->
            var content = tok.content
            while (content.isNotEmpty()) {
                val pos = parseAbbr(content, inline, env)
                if (pos < 0) break
                content = Js.trim(Js.slice(content, pos))
            }
            tok.content = content
            if (content.isEmpty()) {
                open.tight = true
                close.tight = true
            }
        }
    }

    // ---- references: `[label]: href "title"` definitions --------------------------------------

    private fun parseReference(str: String, inline: InlineParser, env: Env): Int {
        if (Js.cc(str, 0) != 0x5B) return -1
        if (str.indexOf("]:") == -1) return -1
        val state = StateInline(str, inline, env, ArrayList())
        val labelEnd = inline.parseLinkLabel(state, 0)
        if (labelEnd < 0 || Js.cc(str, labelEnd + 1) != 0x3A) return -1
        val max = state.posMax
        var pos = labelEnd + 2
        while (pos < max) {
            val code = Js.cc(state.src, pos)
            if (code != 0x20 && code != 0x0A) break
            pos++
        }
        if (!InlineRules.parseLinkDestination(state, pos)) return -1
        val href = state.linkContent
        pos = state.pos
        val start = pos
        pos++
        while (pos < max) {
            val code = Js.cc(state.src, pos)
            if (code != 0x20 && code != 0x0A) break
            pos++
        }
        val title: String
        if (pos < max && start != pos && InlineRules.parseLinkTitle(state, pos)) {
            title = state.linkContent
            pos = state.pos
        } else {
            title = ""
            pos = start
        }
        while (pos < max && Js.cc(state.src, pos) == 0x20) pos++
        if (pos < max && Js.cc(state.src, pos) != 0x0A) return -1
        val label = Utils.normalizeReference(Js.slice(str, 1, labelEnd))
        if (!env.references.containsKey(label)) env.references[label] = href to title
        return pos
    }

    fun references(tokens: List<Token>, inline: InlineParser, env: Env) {
        var i = 1
        val l = tokens.size - 1
        while (i < l) {
            if (tokens[i].type == "inline" && tokens[i - 1].type == "paragraph_open" && tokens[i + 1].type == "paragraph_close") {
                var content = tokens[i].content
                while (content.isNotEmpty()) {
                    val pos = parseReference(content, inline, env)
                    if (pos < 0) break
                    content = Js.trim(Js.slice(content, pos))
                }
                tokens[i].content = content
                if (content.isEmpty()) {
                    tokens[i - 1].tight = true
                    tokens[i + 1].tight = true
                }
            }
            i++
        }
    }

    fun inline(tokens: List<Token>, inline: InlineParser, env: Env) {
        for (tok in tokens) {
            if (tok.type == "inline") inline.parse(tok.content, env, tok.children)
        }
    }

    // ---- footnote_tail ------------------------------------------------------------------------

    fun footnoteTail(stateTokens: MutableList<Token>, env: Env): MutableList<Token> {
        if (!env.footnotesStarted) return stateTokens
        var insideRef = false
        var current: MutableList<Token> = ArrayList()
        var currentLabel = ""
        val refTokens = HashMap<String, MutableList<Token>>()
        val kept = ArrayList<Token>(stateTokens.size)
        for (tok in stateTokens) {
            if (tok.type == "footnote_reference_open") {
                insideRef = true
                current = ArrayList()
                currentLabel = tok.label
                continue
            }
            if (tok.type == "footnote_reference_close") {
                insideRef = false
                refTokens[currentLabel] = current
                continue
            }
            if (insideRef) current.add(tok) else kept.add(tok)
        }
        var tokens: MutableList<Token> = kept
        val list = env.footnoteList ?: return tokens

        var level = 0
        tokens.add(Token("footnote_block_open", level++))
        var noteTokens: List<Token>? = null
        for (i in list.indices) {
            val note = list[i]!!
            tokens.add(Token("footnote_open", level++).also { it.id = i })
            if (note.tokens != null) {
                val t = ArrayList<Token>(3)
                t.add(Token("paragraph_open", level++))
                t.add(Token("inline", level, "").also { it.children = note.tokens })
                t.add(Token("paragraph_close", --level))
                noteTokens = t
            } else if (!note.label.isNullOrEmpty()) {
                noteTokens = refTokens[note.label]
            }
            // `state.tokens.concat(undefined)` appends an undefined token and the next line throws.
            val add = noteTokens ?: throw IllegalStateException("footnote tokens missing for '${note.label}'")
            tokens = ArrayList(tokens).also { it.addAll(add) }
            val lastParagraph = if (tokens.last().type == "paragraph_close") tokens.removeAt(tokens.size - 1) else null
            val count = note.count
            val t = if (count != null && count > 0) count else 1
            for (j in 0 until t) {
                tokens.add(Token("footnote_anchor", level).also {
                    it.id = i
                    it.subId = j
                })
            }
            if (lastParagraph != null) tokens.add(lastParagraph)
            tokens.add(Token("footnote_close", --level))
        }
        tokens.add(Token("footnote_block_close", --level))
        return tokens
    }

    // ---- abbr2: wrap known abbreviations in text tokens ----------------------------------------

    fun abbr2(blockTokens: List<Token>, env: Env) {
        val abbreviations = env.abbreviations ?: return
        val matcher = env.abbrMatcher ?: AbbrMatcher(abbreviations.keys.toList()).also { env.abbrMatcher = it }
        for (blockToken in blockTokens) {
            if (blockToken.type != "inline") continue
            var tokens = blockToken.children
            var i = tokens.size - 1
            while (i >= 0) {
                val token = tokens[i]
                if (token.type != "text") {
                    i--
                    continue
                }
                var pos = 0
                val text = token.content
                var level = token.level
                val nodes = ArrayList<Token>()
                var lastIndex = 0
                while (true) {
                    val m = matcher.exec(text, lastIndex) ?: break
                    lastIndex = m.end
                    if (lastIndex > pos) {
                        nodes.add(Token("text", level, Js.slice(text, pos, m.index + m.prefixLength)))
                    }
                    nodes.add(Token("abbr_open", level++).also { it.title = abbreviations[m.label] })
                    nodes.add(Token("text", level, m.label))
                    nodes.add(Token("abbr_close", --level))
                    pos = lastIndex - m.suffixLength
                    if (m.end == m.index) break // an empty match would loop forever in JS
                }
                if (nodes.isEmpty()) {
                    i--
                    continue
                }
                if (pos < text.length) nodes.add(Token("text", level, Js.slice(text, pos)))
                val replaced = ArrayList<Token>(tokens.size + nodes.size)
                replaced.addAll(tokens.subList(0, i))
                replaced.addAll(nodes)
                replaced.addAll(tokens.subList(i + 1, tokens.size))
                tokens = replaced
                blockToken.children = tokens
                i--
            }
        }
    }
}

/**
 * abbr2's `new RegExp('(^|[PUNCT])(' + labels sorted by length desc + ')($|[PUNCT])', 'g')`.
 * Labels are regex-escaped in JS, so they match literally; this scans with the same alternation
 * order and backtracking.
 */
internal class AbbrMatcher(labels: List<String>) {
    private val alternatives = labels.sortedByDescending { it.length } // stable, like Array.sort

    class Match(val index: Int, val end: Int, val prefixLength: Int, val label: String, val suffixLength: Int)

    private fun isPunct(c: Int) = c >= 0 && PUNCT.indexOf(c.toChar()) >= 0

    private fun tryAt(text: String, i: Int, prefixLength: Int, matchIndex: Int): Match? {
        for (label in alternatives) {
            if (!text.startsWith(label, i)) continue
            val after = i + label.length
            if (after == text.length) return Match(matchIndex, after, prefixLength, label, 0)
            if (isPunct(text[after].code)) return Match(matchIndex, after + 1, prefixLength, label, 1)
        }
        return null
    }

    fun exec(text: String, from: Int): Match? {
        var i = from
        while (i <= text.length) {
            if (i == 0) tryAt(text, 0, 0, 0)?.let { return it }
            if (i < text.length && isPunct(text[i].code)) tryAt(text, i + 1, 1, i)?.let { return it }
            i++
        }
        return null
    }

    private companion object {
        const val PUNCT = " \n()[]'\".,!?-"
    }
}
