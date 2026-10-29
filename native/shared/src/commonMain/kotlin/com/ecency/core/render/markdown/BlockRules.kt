package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
/** remarkable's block rules (lib/rules_block), in the order render-helper runs them. */
internal object BlockRules {
    const val MAX_NESTING = 20

    fun code(state: StateBlock, startLine: Int, endLine: Int, @Suppress("UNUSED_PARAMETER") silent: Boolean): Boolean {
        if (state.tShift[startLine] - state.blkIndent < 4) return false
        var nextLine = startLine + 1
        var last = nextLine
        while (nextLine < endLine) {
            if (state.isEmpty(nextLine)) {
                nextLine++
                continue
            }
            if (state.tShift[nextLine] - state.blkIndent >= 4) {
                nextLine++
                last = nextLine
                continue
            }
            break
        }
        state.line = nextLine
        state.tokens.add(Token("code", state.level, state.getLines(startLine, last, 4 + state.blkIndent, true)).also { it.block = true })
        return true
    }

    fun fences(state: StateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        var haveEndMarker = false
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        if (pos + 3 > max) return false
        val marker = Js.cc(state.src, pos)
        if (marker != 0x7E && marker != 0x60) return false
        var mem = pos
        pos = state.skipChars(pos, marker)
        var len = pos - mem
        if (len < 3) return false
        val params = Js.trim(Js.slice(state.src, pos, max))
        if (params.indexOf('`') >= 0) return false
        if (silent) return true

        var nextLine = startLine
        while (true) {
            nextLine++
            if (nextLine >= endLine) break
            mem = state.bMarks[nextLine] + state.tShift[nextLine]
            pos = mem
            max = state.eMarks[nextLine]
            if (pos < max && state.tShift[nextLine] < state.blkIndent) break
            if (Js.cc(state.src, pos) != marker) continue
            if (state.tShift[nextLine] - state.blkIndent >= 4) continue
            pos = state.skipChars(pos, marker)
            if (pos - mem < len) continue
            pos = state.skipSpaces(pos)
            if (pos < max) continue
            haveEndMarker = true
            break
        }
        len = state.tShift[startLine]
        state.line = nextLine + (if (haveEndMarker) 1 else 0)
        state.tokens.add(Token("fence", state.level, state.getLines(startLine + 1, nextLine, len, true)).also { it.params = params })
        return true
    }

    fun blockquote(state: StateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        if (pos > max) return false
        if (Js.cc(state.src, pos++) != 0x3E) return false
        if (state.level >= MAX_NESTING) return false
        if (silent) return true

        if (Js.cc(state.src, pos) == 0x20) pos++
        val oldIndent = state.blkIndent
        state.blkIndent = 0
        val oldBMarks = arrayListOf(state.bMarks[startLine])
        state.bMarks[startLine] = pos
        pos = if (pos < max) state.skipSpaces(pos) else pos
        var lastLineEmpty = pos >= max
        val oldTShift = arrayListOf(state.tShift[startLine])
        state.tShift[startLine] = pos - state.bMarks[startLine]
        val terminatorRules = state.parser.blockquoteTerminators

        var nextLine = startLine + 1
        while (nextLine < endLine) {
            pos = state.bMarks[nextLine] + state.tShift[nextLine]
            max = state.eMarks[nextLine]
            if (pos >= max) break
            if (Js.cc(state.src, pos++) == 0x3E) {
                if (Js.cc(state.src, pos) == 0x20) pos++
                oldBMarks.add(state.bMarks[nextLine])
                state.bMarks[nextLine] = pos
                pos = if (pos < max) state.skipSpaces(pos) else pos
                lastLineEmpty = pos >= max
                oldTShift.add(state.tShift[nextLine])
                state.tShift[nextLine] = pos - state.bMarks[nextLine]
                nextLine++
                continue
            }
            if (lastLineEmpty) break
            if (terminatorRules.any { it(state, nextLine, endLine, true) }) break
            oldBMarks.add(state.bMarks[nextLine])
            oldTShift.add(state.tShift[nextLine])
            state.tShift[nextLine] = -1337
            nextLine++
        }

        val oldParentType = state.parentType
        state.parentType = "blockquote"
        state.tokens.add(Token("blockquote_open", state.level++))
        state.parser.tokenize(state, startLine, nextLine)
        state.tokens.add(Token("blockquote_close", --state.level))
        state.parentType = oldParentType

        for (i in oldTShift.indices) {
            state.bMarks[i + startLine] = oldBMarks[i]
            state.tShift[i + startLine] = oldTShift[i]
        }
        state.blkIndent = oldIndent
        return true
    }

    fun hr(state: StateBlock, startLine: Int, @Suppress("UNUSED_PARAMETER") endLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine]
        val max = state.eMarks[startLine]
        pos += state.tShift[startLine]
        if (pos > max) return false
        val marker = Js.cc(state.src, pos++)
        if (marker != 0x2A && marker != 0x2D && marker != 0x5F) return false
        var cnt = 1
        while (pos < max) {
            val ch = Js.cc(state.src, pos++)
            if (ch != marker && ch != 0x20) return false
            if (ch == marker) cnt++
        }
        if (cnt < 3) return false
        if (silent) return true
        state.line = startLine + 1
        state.tokens.add(Token("hr", state.level))
        return true
    }

    private fun skipBulletListMarker(state: StateBlock, startLine: Int): Int {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        val max = state.eMarks[startLine]
        if (pos >= max) return -1
        val marker = Js.cc(state.src, pos++)
        if (marker != 0x2A && marker != 0x2D && marker != 0x2B) return -1
        if (pos < max && Js.cc(state.src, pos) != 0x20) return -1
        return pos
    }

    private fun skipOrderedListMarker(state: StateBlock, startLine: Int): Int {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        val max = state.eMarks[startLine]
        if (pos + 1 >= max) return -1
        var ch = Js.cc(state.src, pos++)
        if (ch < 0x30 || ch > 0x39) return -1
        while (true) {
            if (pos >= max) return -1
            ch = Js.cc(state.src, pos++)
            if (ch in 0x30..0x39) continue
            if (ch == 0x29 || ch == 0x2E) break
            return -1
        }
        if (pos < max && Js.cc(state.src, pos) != 0x20) return -1
        return pos
    }

    private fun markTightParagraphs(state: StateBlock, idx: Int) {
        val level = state.level + 2
        var i = idx + 2
        val l = state.tokens.size - 2
        while (i < l) {
            if (state.tokens[i].level == level && state.tokens[i].type == "paragraph_open") {
                state.tokens[i + 2].tight = true
                state.tokens[i].tight = true
                i += 2
            }
            i++
        }
    }

    fun list(state: StateBlock, startLineIn: Int, endLine: Int, silent: Boolean): Boolean {
        var startLine = startLineIn
        var tight = true
        val isOrdered: Boolean
        var posAfterMarker = skipOrderedListMarker(state, startLine)
        if (posAfterMarker >= 0) {
            isOrdered = true
        } else {
            posAfterMarker = skipBulletListMarker(state, startLine)
            if (posAfterMarker >= 0) isOrdered = false else return false
        }
        if (state.level >= MAX_NESTING) return false
        val markerCharCode = Js.cc(state.src, posAfterMarker - 1)
        if (silent) return true

        val listTokIdx = state.tokens.size
        if (isOrdered) {
            val start = state.bMarks[startLine] + state.tShift[startLine]
            val digits = Js.substr(state.src, start, posAfterMarker - start - 1)
            state.tokens.add(Token("ordered_list_open", state.level++).also { it.order = digits })
        } else {
            state.tokens.add(Token("bullet_list_open", state.level++))
        }

        var nextLine = startLine
        var prevEmptyEnd = false
        val terminatorRules = state.parser.listTerminators

        while (nextLine < endLine) {
            val contentStart = state.skipSpaces(posAfterMarker)
            val max = state.eMarks[nextLine]
            var indentAfterMarker = if (contentStart >= max) 1 else contentStart - posAfterMarker
            if (indentAfterMarker > 4) indentAfterMarker = 1
            if (indentAfterMarker < 1) indentAfterMarker = 1
            val indent = (posAfterMarker - state.bMarks[nextLine]) + indentAfterMarker

            state.tokens.add(Token("list_item_open", state.level++))
            val oldIndent = state.blkIndent
            val oldTight = state.tight
            val oldTShift = state.tShift[startLine]
            val oldParentType = state.parentType
            state.tShift[startLine] = contentStart - state.bMarks[startLine]
            state.blkIndent = indent
            state.tight = true
            state.parentType = "list"

            state.parser.tokenize(state, startLine, endLine)

            if (!state.tight || prevEmptyEnd) tight = false
            prevEmptyEnd = (state.line - startLine) > 1 && state.isEmpty(state.line - 1)

            state.blkIndent = oldIndent
            state.tShift[startLine] = oldTShift
            state.tight = oldTight
            state.parentType = oldParentType

            state.tokens.add(Token("list_item_close", --state.level))

            startLine = state.line
            nextLine = startLine
            if (nextLine >= endLine) break
            if (state.isEmpty(nextLine)) break
            if (state.tShift[nextLine] < state.blkIndent) break
            if (terminatorRules.any { it(state, nextLine, endLine, true) }) break
            if (isOrdered) {
                posAfterMarker = skipOrderedListMarker(state, nextLine)
                if (posAfterMarker < 0) break
            } else {
                posAfterMarker = skipBulletListMarker(state, nextLine)
                if (posAfterMarker < 0) break
            }
            if (markerCharCode != Js.cc(state.src, posAfterMarker - 1)) break
        }

        state.tokens.add(Token(if (isOrdered) "ordered_list_close" else "bullet_list_close", --state.level))
        state.line = nextLine
        if (tight) markTightParagraphs(state, listTokIdx)
        return true
    }

    fun footnote(state: StateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        val start = state.bMarks[startLine] + state.tShift[startLine]
        val max = state.eMarks[startLine]
        if (start + 4 > max) return false
        if (Js.cc(state.src, start) != 0x5B) return false
        if (Js.cc(state.src, start + 1) != 0x5E) return false
        if (state.level >= MAX_NESTING) return false
        var pos = start + 2
        while (pos < max) {
            if (Js.cc(state.src, pos) == 0x20) return false
            if (Js.cc(state.src, pos) == 0x5D) break
            pos++
        }
        if (pos == start + 2) return false
        if (pos + 1 >= max || Js.cc(state.src, ++pos) != 0x3A) return false
        if (silent) return true
        pos++

        state.env.footnotesStarted = true
        val refs = state.env.footnoteRefs ?: HashMap<String, Int>().also { state.env.footnoteRefs = it }
        val label = Js.slice(state.src, start + 2, pos - 2)
        refs[label] = -1

        state.tokens.add(Token("footnote_reference_open", state.level++).also { it.label = label })
        val oldBMark = state.bMarks[startLine]
        val oldTShift = state.tShift[startLine]
        val oldParentType = state.parentType
        state.tShift[startLine] = state.skipSpaces(pos) - pos
        state.bMarks[startLine] = pos
        state.blkIndent += 4
        state.parentType = "footnote"
        if (state.tShift[startLine] < state.blkIndent) {
            state.tShift[startLine] = state.tShift[startLine] + state.blkIndent
            state.bMarks[startLine] = state.bMarks[startLine] - state.blkIndent
        }
        state.parser.tokenize(state, startLine, endLine)
        state.parentType = oldParentType
        state.blkIndent -= 4
        state.tShift[startLine] = oldTShift
        state.bMarks[startLine] = oldBMark
        state.tokens.add(Token("footnote_reference_close", --state.level))
        return true
    }

    fun heading(state: StateBlock, startLine: Int, @Suppress("UNUSED_PARAMETER") endLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine] + state.tShift[startLine]
        var max = state.eMarks[startLine]
        if (pos >= max) return false
        var ch = Js.cc(state.src, pos)
        if (ch != 0x23 || pos >= max) return false
        var level = 1
        ch = Js.cc(state.src, ++pos)
        while (ch == 0x23 && pos < max && level <= 6) {
            level++
            ch = Js.cc(state.src, ++pos)
        }
        if (level > 6 || (pos < max && ch != 0x20)) return false
        if (silent) return true

        max = state.skipCharsBack(max, 0x20, pos)
        val tmp = state.skipCharsBack(max, 0x23, pos)
        if (tmp > pos && Js.cc(state.src, tmp - 1) == 0x20) max = tmp
        state.line = startLine + 1
        state.tokens.add(Token("heading_open", state.level).also { it.hLevel = level })
        if (pos < max) {
            state.tokens.add(Token.inline(Js.trim(Js.slice(state.src, pos, max)), state.level + 1))
        }
        state.tokens.add(Token("heading_close", state.level).also { it.hLevel = level })
        return true
    }

    fun lheading(state: StateBlock, startLine: Int, endLine: Int, @Suppress("UNUSED_PARAMETER") silent: Boolean): Boolean {
        val next = startLine + 1
        if (next >= endLine) return false
        if (state.tShift[next] < state.blkIndent) return false
        if (state.tShift[next] - state.blkIndent > 3) return false
        var pos = state.bMarks[next] + state.tShift[next]
        val max = state.eMarks[next]
        if (pos >= max) return false
        val marker = Js.cc(state.src, pos)
        if (marker != 0x2D && marker != 0x3D) return false
        pos = state.skipChars(pos, marker)
        pos = state.skipSpaces(pos)
        if (pos < max) return false
        pos = state.bMarks[startLine] + state.tShift[startLine]
        state.line = next + 1
        val hLevel = if (marker == 0x3D) 1 else 2
        state.tokens.add(Token("heading_open", state.level).also { it.hLevel = hLevel })
        state.tokens.add(Token.inline(Js.trim(Js.slice(state.src, pos, state.eMarks[startLine])), state.level + 1))
        state.tokens.add(Token("heading_close", state.level).also { it.hLevel = hLevel })
        return true
    }

    private val HTML_BLOCKS = setOf(
        "article", "aside", "button", "blockquote", "body", "canvas", "caption", "col", "colgroup", "dd", "div",
        "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5",
        "h6", "header", "hgroup", "hr", "iframe", "li", "map", "object", "ol", "output", "p", "pre", "progress",
        "script", "section", "style", "table", "tbody", "td", "textarea", "tfoot", "th", "tr", "thead", "ul", "video",
    )

    private fun isAsciiLetter(c: Int) = (c in 0x41..0x5A) || (c in 0x61..0x7A)

    /**
     * HTML_TAG_OPEN_RE `^<([a-zA-Z]{1,15})[\s\/>]` and HTML_TAG_CLOSE_RE `^<\/([a-zA-Z]{1,15})[\s>]`
     * on `src.slice(pos, max)`. Returns the tag name or null.
     */
    private fun htmlBlockTagName(src: String, pos: Int, max: Int, closing: Boolean): String? {
        val nameStart = pos + if (closing) 2 else 1
        var i = nameStart
        while (i < max && isAsciiLetter(src[i].code)) i++
        val n = i - nameStart
        if (n < 1 || n > 15 || i >= max) return null
        val c = src[i].code
        if (!(Js.isSpace(c) || c == 0x3E || (!closing && c == 0x2F))) return null
        return src.substring(nameStart, i)
    }

    fun htmlblock(state: StateBlock, startLine: Int, @Suppress("UNUSED_PARAMETER") endLine: Int, silent: Boolean): Boolean {
        var pos = state.bMarks[startLine]
        val max = state.eMarks[startLine]
        val shift = state.tShift[startLine]
        pos += shift
        if (shift > 3 || pos + 2 >= max) return false
        if (Js.cc(state.src, pos) != 0x3C) return false
        val ch = Js.cc(state.src, pos + 1)
        if (ch == 0x21 || ch == 0x3F) {
            if (silent) return true
        } else if (ch == 0x2F || isAsciiLetter(ch)) {
            val name = htmlBlockTagName(state.src, pos, max, ch == 0x2F) ?: return false
            if (name.lowercase() !in HTML_BLOCKS) return false
            if (silent) return true
        } else {
            return false
        }
        var nextLine = startLine + 1
        while (nextLine < state.lineMax && !state.isEmpty(nextLine)) nextLine++
        state.line = nextLine
        state.tokens.add(Token("htmlblock", state.level, state.getLines(startLine, nextLine, 0, true)))
        return true
    }

    private fun getLine(state: StateBlock, line: Int): String {
        val pos = state.bMarks[line] + state.blkIndent
        val max = state.eMarks[line]
        return Js.substr(state.src, pos, max - pos)
    }

    /** `str.replace(/^\||\|$/g, '')`. */
    private fun stripOuterPipes(s: String): String {
        val start = if (s.startsWith("|")) 1 else 0
        val end = if (s.length > start && s.endsWith("|")) s.length - 1 else s.length
        return s.substring(start, end)
    }

    fun table(state: StateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        if (startLine + 2 > endLine) return false
        var nextLine = startLine + 1
        if (state.tShift[nextLine] < state.blkIndent) return false
        val pos = state.bMarks[nextLine] + state.tShift[nextLine]
        if (pos >= state.eMarks[nextLine]) return false
        val ch = Js.cc(state.src, pos)
        if (ch != 0x7C && ch != 0x2D && ch != 0x3A) return false
        var lineText = getLine(state, startLine + 1)
        if (lineText.isEmpty() || lineText.any { it != '-' && it != ':' && it != '|' && it != ' ' }) return false
        var rows = lineText.split('|')
        // `rows <= 2` compares the joined array as a number: always NaN here, so never true.
        val aligns = ArrayList<String>()
        for (i in rows.indices) {
            val t = Js.trim(rows[i])
            if (t.isEmpty()) {
                if (i == 0 || i == rows.size - 1) continue else return false
            }
            if (!isAlignSpec(t)) return false
            if (t[t.length - 1] == ':') {
                aligns.add(if (t[0] == ':') "center" else "right")
            } else if (t[0] == ':') {
                aligns.add("left")
            } else {
                aligns.add("")
            }
        }
        lineText = Js.trim(getLine(state, startLine))
        if (lineText.indexOf('|') == -1) return false
        rows = stripOuterPipes(lineText).split('|')
        if (aligns.size != rows.size) return false
        if (silent) return true

        state.tokens.add(Token("table_open", state.level++))
        state.tokens.add(Token("thead_open", state.level++))
        state.tokens.add(Token("tr_open", state.level++))
        for (i in rows.indices) {
            state.tokens.add(Token("th_open", state.level++).also { it.align = aligns[i] })
            state.tokens.add(Token.inline(Js.trim(rows[i]), state.level))
            state.tokens.add(Token("th_close", --state.level))
        }
        state.tokens.add(Token("tr_close", --state.level))
        state.tokens.add(Token("thead_close", --state.level))
        state.tokens.add(Token("tbody_open", state.level++))

        nextLine = startLine + 2
        while (nextLine < endLine) {
            if (state.tShift[nextLine] < state.blkIndent) break
            lineText = Js.trim(getLine(state, nextLine))
            if (lineText.indexOf('|') == -1) break
            rows = stripOuterPipes(lineText).split('|')
            state.tokens.add(Token("tr_open", state.level++))
            for (i in rows.indices) {
                state.tokens.add(Token("td_open", state.level++).also { it.align = aligns.getOrElse(i) { "" } })
                val r = rows[i]
                val cell = r.substring(if (Js.cc(r, 0) == 0x7C) 1 else 0, if (Js.cc(r, r.length - 1) == 0x7C) r.length - 1 else r.length)
                state.tokens.add(Token.inline(Js.trim(cell), state.level))
                state.tokens.add(Token("td_close", --state.level))
            }
            state.tokens.add(Token("tr_close", --state.level))
            nextLine++
        }
        state.tokens.add(Token("tbody_close", --state.level))
        state.tokens.add(Token("table_close", --state.level))
        state.line = nextLine
        return true
    }

    /** `/^:?-+:?$/`. */
    private fun isAlignSpec(t: String): Boolean {
        var i = 0
        var end = t.length
        if (t[0] == ':') i++
        if (end > i && t[end - 1] == ':') end--
        if (i >= end) return false
        for (k in i until end) if (t[k] != '-') return false
        return true
    }

    private fun skipMarker(state: StateBlock, line: Int): Int {
        var start = state.bMarks[line] + state.tShift[line]
        val max = state.eMarks[line]
        if (start >= max) return -1
        val marker = Js.cc(state.src, start++)
        if (marker != 0x7E && marker != 0x3A) return -1
        val pos = state.skipSpaces(start)
        if (start == pos) return -1
        if (pos >= max) return -1
        return pos
    }

    fun deflist(state: StateBlock, startLine: Int, endLine: Int, silent: Boolean): Boolean {
        if (silent) {
            if (state.ddIndent < 0) return false
            return skipMarker(state, startLine) >= 0
        }
        var nextLine = startLine + 1
        if (state.isEmpty(nextLine)) {
            if (++nextLine > endLine) return false
        }
        if (state.tShift[nextLine] < state.blkIndent) return false
        var contentStart = skipMarker(state, nextLine)
        if (contentStart < 0) return false
        if (state.level >= MAX_NESTING) return false

        val listTokIdx = state.tokens.size
        state.tokens.add(Token("dl_open", state.level++))
        var dtLine = startLine
        var ddLine = nextLine
        var tight = true

        outer@ while (true) {
            tight = true
            var prevEmptyEnd = false
            state.tokens.add(Token("dt_open", state.level++))
            state.tokens.add(Token.inline(Js.trim(state.getLines(dtLine, dtLine + 1, state.blkIndent, false)), state.level + 1))
            state.tokens.add(Token("dt_close", --state.level))

            while (true) {
                state.tokens.add(Token("dd_open", state.level++))
                val oldTight = state.tight
                val oldDDIndent = state.ddIndent
                val oldIndent = state.blkIndent
                val oldTShift = state.tShift[ddLine]
                val oldParentType = state.parentType
                state.ddIndent = state.tShift[ddLine] + 2
                state.blkIndent = state.ddIndent
                state.tShift[ddLine] = contentStart - state.bMarks[ddLine]
                state.tight = true
                state.parentType = "deflist"

                state.parser.tokenize(state, ddLine, endLine)

                if (!state.tight || prevEmptyEnd) tight = false
                prevEmptyEnd = (state.line - ddLine) > 1 && state.isEmpty(state.line - 1)

                state.tShift[ddLine] = oldTShift
                state.tight = oldTight
                state.parentType = oldParentType
                state.blkIndent = oldIndent
                state.ddIndent = oldDDIndent

                state.tokens.add(Token("dd_close", --state.level))
                nextLine = state.line
                if (nextLine >= endLine) break@outer
                if (state.tShift[nextLine] < state.blkIndent) break@outer
                contentStart = skipMarker(state, nextLine)
                if (contentStart < 0) break
                ddLine = nextLine
            }

            if (nextLine >= endLine) break
            dtLine = nextLine
            if (state.isEmpty(dtLine)) break
            if (state.tShift[dtLine] < state.blkIndent) break
            ddLine = dtLine + 1
            if (ddLine >= endLine) break
            if (state.isEmpty(ddLine)) ddLine++
            if (ddLine >= endLine) break
            if (state.tShift[ddLine] < state.blkIndent) break
            contentStart = skipMarker(state, ddLine)
            if (contentStart < 0) break
        }

        state.tokens.add(Token("dl_close", --state.level))
        state.line = nextLine
        if (tight) markTightParagraphs(state, listTokIdx)
        return true
    }

    fun paragraph(state: StateBlock, startLine: Int, @Suppress("UNUSED_PARAMETER") endLineIn: Int, @Suppress("UNUSED_PARAMETER") silent: Boolean): Boolean {
        var nextLine = startLine + 1
        val endLine = state.lineMax
        if (nextLine < endLine && !state.isEmpty(nextLine)) {
            val terminatorRules = state.parser.paragraphTerminators
            while (nextLine < endLine && !state.isEmpty(nextLine)) {
                if (state.tShift[nextLine] - state.blkIndent > 3) {
                    nextLine++
                    continue
                }
                if (terminatorRules.any { it(state, nextLine, endLine, true) }) break
                nextLine++
            }
        }
        val content = Js.trim(state.getLines(startLine, nextLine, state.blkIndent, false))
        state.line = nextLine
        if (content.isNotEmpty()) {
            state.tokens.add(Token("paragraph_open", state.level))
            state.tokens.add(Token.inline(content, state.level + 1))
            state.tokens.add(Token("paragraph_close", state.level))
        }
        return true
    }
}
