package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
internal typealias BlockRule = (state: StateBlock, startLine: Int, endLine: Int, silent: Boolean) -> Boolean

/** remarkable's StateBlock. */
internal class StateBlock(
    val src: String,
    val parser: BlockParser,
    val inline: InlineParser,
    val env: Env,
    val tokens: MutableList<Token>,
) {
    val bMarks = ArrayList<Int>()
    val eMarks = ArrayList<Int>()
    val tShift = ArrayList<Int>()

    var blkIndent = 0
    var line = 0
    val lineMax: Int
    var tight = false
    var parentType = "root"
    var ddIndent = -1
    var level = 0

    init {
        val s = src
        var indent = 0
        var indentFound = false
        var start = 0
        var pos = 0
        val len = s.length
        while (pos < len) {
            val ch = s[pos].code
            if (!indentFound) {
                if (ch == 0x20) {
                    indent++
                    pos++
                    continue
                } else {
                    indentFound = true
                }
            }
            if (ch == 0x0A || pos == len - 1) {
                if (ch != 0x0A) pos++
                bMarks.add(start)
                eMarks.add(pos)
                tShift.add(indent)
                indentFound = false
                indent = 0
                start = pos + 1
            }
            pos++
        }
        bMarks.add(s.length)
        eMarks.add(s.length)
        tShift.add(0)
        lineMax = bMarks.size - 1
    }

    fun isEmpty(line: Int): Boolean =
        line in 0..lineMax && bMarks[line] + tShift[line] >= eMarks[line] // out of range: NaN in JS

    fun skipEmptyLines(from: Int): Int {
        var f = from
        while (f < lineMax) {
            if (bMarks[f] + tShift[f] < eMarks[f]) break
            f++
        }
        return f
    }

    fun skipSpaces(pos: Int): Int {
        var p = pos
        while (p < src.length) {
            if (src[p].code != 0x20) break
            p++
        }
        return p
    }

    fun skipChars(pos: Int, code: Int): Int {
        var p = pos
        while (p < src.length) {
            if (src[p].code != code) break
            p++
        }
        return p
    }

    fun skipCharsBack(pos: Int, code: Int, min: Int): Int {
        var p = pos
        if (p <= min) return p
        while (p > min) {
            if (code != Js.cc(src, --p)) return p + 1
        }
        return p
    }

    fun getLines(begin: Int, end: Int, indent: Int, keepLastLF: Boolean): String {
        if (begin >= end) return ""
        if (begin + 1 == end) {
            val first = bMarks[begin] + minOf(tShift[begin], indent)
            val last = if (keepLastLF) eMarks[begin] + 1 else eMarks[begin]
            return Js.slice(src, first, last)
        }
        val sb = StringBuilder()
        var line = begin
        while (line < end) {
            var shift = tShift[line]
            if (shift > indent) shift = indent
            if (shift < 0) shift = 0
            val first = bMarks[line] + shift
            val last = if (line + 1 < end || keepLastLF) eMarks[line] + 1 else eMarks[line]
            sb.append(Js.slice(src, first, last))
            line++
        }
        return sb.toString()
    }
}

/** remarkable's ParserBlock with the rule set render-helper enables (all block rules). */
internal class BlockParser(full: Boolean = true) {
    // `full` is render-helper's set (deflist enabled); false is remarkable's default preset.
    private val rules: List<BlockRule> = listOfNotNull(
        BlockRules::code, BlockRules::fences, BlockRules::blockquote, BlockRules::hr, BlockRules::list,
        BlockRules::footnote, BlockRules::heading, BlockRules::lheading, BlockRules::htmlblock,
        BlockRules::table, if (full) BlockRules::deflist else null, BlockRules::paragraph,
    )

    // Terminator chains ("alt" lists), in rule order, enabled rules only.
    val paragraphTerminators: List<BlockRule> = listOfNotNull(
        BlockRules::fences, BlockRules::blockquote, BlockRules::hr, BlockRules::list, BlockRules::footnote,
        BlockRules::heading, BlockRules::htmlblock, BlockRules::table, if (full) BlockRules::deflist else null,
    )
    val blockquoteTerminators: List<BlockRule> = listOf(
        BlockRules::fences, BlockRules::blockquote, BlockRules::hr, BlockRules::list, BlockRules::heading,
        BlockRules::htmlblock,
    )
    val listTerminators: List<BlockRule> = listOf(BlockRules::fences, BlockRules::blockquote, BlockRules::hr)

    fun tokenize(state: StateBlock, startLine: Int, endLine: Int) {
        var line = startLine
        var hasEmptyLines = false
        while (line < endLine) {
            line = state.skipEmptyLines(line)
            state.line = line
            if (line >= endLine) break
            if (state.tShift[line] < state.blkIndent) break
            for (rule in rules) {
                if (rule(state, line, endLine, false)) break
            }
            state.tight = !hasEmptyLines
            if (state.isEmpty(state.line - 1)) hasEmptyLines = true
            line = state.line
            if (line < endLine && state.isEmpty(line)) {
                hasEmptyLines = true
                line++
                if (line < endLine && state.parentType == "list" && state.isEmpty(line)) break
                state.line = line
            }
        }
    }

    fun parse(input: String, inline: InlineParser, env: Env, outTokens: MutableList<Token>) {
        if (input.isEmpty()) return
        var str = normalize(input)
        if (str.indexOf('\t') >= 0) str = expandTabs(str)
        val state = StateBlock(str, this, inline, env, outTokens)
        tokenize(state, state.line, state.lineMax)
    }

    /** ` ` to space, then NEWLINES_RE (`\r[\n\u0085]|[␤ \u0085]`) to `\n`. */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' -> sb.append(' ')
                c == '\r' && i + 1 < s.length && (s[i + 1] == '\n' || s[i + 1] == '\u0085') -> {
                    sb.append('\n')
                    i++
                }
                c == '␤' || c == ' ' || c == '\u0085' -> sb.append('\n')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** remarkable's tab expansion (TABS_SCAN_RE replacer), quirks included. */
    private fun expandTabs(str: String): String {
        val sb = StringBuilder(str.length + 16)
        var lineStart = 0
        var lastTabPos = 0
        for (offset in str.indices) {
            val c = str[offset]
            if (c == '\n') {
                lineStart = offset + 1
                lastTabPos = 0
                sb.append(c)
            } else if (c == '\t') {
                sb.append("    ".substring((offset - lineStart - lastTabPos) % 4))
                lastTabPos = offset - lineStart + 1
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
