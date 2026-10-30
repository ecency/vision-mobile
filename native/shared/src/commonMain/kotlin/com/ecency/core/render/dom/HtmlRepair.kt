package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
/**
 * The repair step render-helper runs when xmldom rejects a body:
 * `domSerializer(htmlparser2.parseDocument(html, { lowerCaseTags: false, lowerCaseAttributeNames: false }).children)`.
 *
 * Ports htmlparser2 10.1.0 (Tokenizer + Parser), the parts of domhandler 5 it drives, and
 * dom-serializer 2.0.0 `render` with default options (entities 4.5 `encodeXML`). Input is
 * always the output of render-helper's sanitizer, so SVG/MathML foreign-content name fixups
 * of dom-serializer are not ported (no such tag survives the sanitizer whitelist).
 */
internal object HtmlRepair {
    fun repair(html: String): String {
        val root = HParser(html).parse()
        val sb = StringBuilder(html.length + 16)
        for (child in root.children) render(child, sb)
        return sb.toString()
    }

    // ---- domhandler nodes --------------------------------------------------------------

    abstract class HNode {
        var parent: HElement? = null
    }

    class HElement(val name: String?, val attribs: List<Pair<String, String>>) : HNode() {
        val children = ArrayList<HNode>()
    }

    class HText(data: String) : HNode() {
        val buf = StringBuilder(data)
        val data: String get() = buf.toString()
    }
    class HComment(var data: String) : HNode()
    class HCData : HNode() {
        val text = HText("")
    }

    class HDirective(val data: String) : HNode()

    // ---- dom-serializer -----------------------------------------------------------------

    private val SINGLE_TAG = setOf(
        "area", "base", "basefont", "br", "col", "command", "embed", "frame", "hr", "img", "input",
        "isindex", "keygen", "link", "meta", "param", "source", "track", "wbr",
    )
    private val UNENCODED = setOf("style", "script", "xmp", "iframe", "noembed", "noframes", "plaintext", "noscript")

    /** dom-serializer `render`, iterative so deeply nested bodies cannot overflow the stack. */
    private fun render(root: HNode, sb: StringBuilder) {
        val stack = ArrayList<Any>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            when (node) {
                is String -> sb.append(node)
                is HElement -> {
                    val name = node.name!!
                    sb.append('<').append(name)
                    val attrs = orderedAttribs(node.attribs)
                    if (attrs.isNotEmpty()) {
                        sb.append(' ')
                        attrs.forEachIndexed { i, (k, v) ->
                            if (i > 0) sb.append(' ')
                            sb.append(k)
                            if (v.isNotEmpty()) {
                                sb.append("=\"")
                                encodeXml(v, sb)
                                sb.append('"')
                            }
                        }
                    }
                    sb.append('>')
                    if (name !in SINGLE_TAG) stack.add("</$name>")
                    for (k in node.children.size - 1 downTo 0) stack.add(node.children[k])
                }
                is HText -> {
                    val parentName = node.parent?.name
                    if (parentName != null && parentName in UNENCODED) sb.append(node.buf) else encodeXml(node.data, sb)
                }
                is HComment -> sb.append("<!--").append(node.data).append("-->")
                is HCData -> sb.append("<![CDATA[").append(node.text.data).append("]]>")
                is HDirective -> sb.append('<').append(node.data).append('>')
            }
        }
    }

    /** `Object.keys` order: canonical array-index keys first, ascending, then insertion order. */
    private fun orderedAttribs(attribs: List<Pair<String, String>>): List<Pair<String, String>> {
        if (attribs.none { isArrayIndex(it.first) }) return attribs
        val (idx, rest) = attribs.partition { isArrayIndex(it.first) }
        return idx.sortedBy { it.first.toLong() } + rest
    }

    private fun isArrayIndex(k: String): Boolean {
        if (k.isEmpty() || k.length > 10) return false
        if (k.length > 1 && k[0] == '0') return false
        if (!k.all(Js::isDigit)) return false
        return k.toLong() < 4294967295L
    }

    /** entities 4.5.0 `encodeXML`, including its lone-high-surrogate skip. */
    fun encodeXml(str: String, sb: StringBuilder) {
        var i = 0
        while (i < str.length) {
            val c = str[i]
            when {
                c == '"' -> sb.append("&quot;")
                c == '&' -> sb.append("&amp;")
                c == '\'' -> sb.append("&apos;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '$' || c.code >= 0x80 -> {
                    sb.append("&#x").append(Js.codePointAt(str, i).toString(16)).append(';')
                    if ((c.code and 0xFC00) == 0xD800) i++
                }
                else -> sb.append(c)
            }
            i++
        }
    }

    // ---- htmlparser2 Parser + Tokenizer, driving a domhandler tree ---------------------

    private val P_TAG = setOf("p")
    private val FORM_TAGS = setOf("input", "option", "optgroup", "select", "button", "datalist", "textarea")
    private val TABLE_SECTION = setOf("thead", "tbody")
    private val DDT = setOf("dd", "dt")
    private val RTP = setOf("rt", "rp")
    private val OPEN_IMPLIES_CLOSE: Map<String, Set<String>> = buildMap {
        put("tr", setOf("tr", "th", "td"))
        put("th", setOf("th"))
        put("td", setOf("thead", "th", "td"))
        put("body", setOf("head", "link", "script"))
        put("li", setOf("li"))
        for (t in listOf("p", "h1", "h2", "h3", "h4", "h5", "h6")) put(t, P_TAG)
        for (t in listOf("select", "input", "output", "button", "datalist", "textarea")) put(t, FORM_TAGS)
        put("option", setOf("option"))
        put("optgroup", setOf("optgroup", "option"))
        put("dd", DDT)
        put("dt", DDT)
        for (t in listOf(
            "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset", "figcaption",
            "figure", "footer", "form", "header", "hr", "main", "nav", "ol", "pre", "section", "table", "ul",
        )) put(t, P_TAG)
        put("rt", RTP)
        put("rp", RTP)
        put("tbody", TABLE_SECTION)
        put("tfoot", TABLE_SECTION)
    }
    private val FOREIGN_CONTEXT = setOf("math", "svg")
    private val HTML_INTEGRATION = setOf("mi", "mo", "mn", "ms", "mtext", "annotation-xml", "foreignobject", "desc", "title")

    private const val TEXT = 1
    private const val BEFORE_TAG_NAME = 2
    private const val IN_TAG_NAME = 3
    private const val IN_SELF_CLOSING_TAG = 4
    private const val BEFORE_CLOSING_TAG_NAME = 5
    private const val IN_CLOSING_TAG_NAME = 6
    private const val AFTER_CLOSING_TAG_NAME = 7
    private const val BEFORE_ATTRIBUTE_NAME = 8
    private const val IN_ATTRIBUTE_NAME = 9
    private const val AFTER_ATTRIBUTE_NAME = 10
    private const val BEFORE_ATTRIBUTE_VALUE = 11
    private const val IN_ATTRIBUTE_VALUE_DQ = 12
    private const val IN_ATTRIBUTE_VALUE_SQ = 13
    private const val IN_ATTRIBUTE_VALUE_NQ = 14
    private const val BEFORE_DECLARATION = 15
    private const val IN_DECLARATION = 16
    private const val IN_PROCESSING_INSTRUCTION = 17
    private const val BEFORE_COMMENT = 18
    private const val CDATA_SEQUENCE = 19
    private const val IN_SPECIAL_COMMENT = 20
    private const val IN_COMMENT_LIKE = 21
    private const val BEFORE_SPECIAL_S = 22
    private const val BEFORE_SPECIAL_T = 23
    private const val SPECIAL_START_SEQUENCE = 24
    private const val IN_SPECIAL_TAG = 25
    private const val IN_ENTITY = 26

    private const val CDATA = "CDATA["
    private const val CDATA_END = "]]>"
    private const val COMMENT_END = "-->"
    private const val SCRIPT_END = "</script"
    private const val STYLE_END = "</style"
    private const val TITLE_END = "</title"
    private const val TEXTAREA_END = "</textarea"
    private const val XMP_END = "</xmp"

    private fun isWhitespace(c: Int) = c == 32 || c == 10 || c == 9 || c == 12 || c == 13
    private fun isEndOfTagSection(c: Int) = c == '/'.code || c == '>'.code || isWhitespace(c)
    private fun isAsciiAlpha(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    private class HParser(private val buffer: String) {
        // Tokenizer state
        private var state = TEXT
        private var sectionStart = 0
        private var index = 0
        private var entityStart = 0
        private var baseState = TEXT
        private var isSpecial = false
        private var currentSequence = ""
        private var sequenceIndex = 0
        private val entityDecoder = EntityDecoder(EntityDecoder.V7) { cp, consumed -> emitCodePoint(cp, consumed) }

        // Parser state
        private var tagname = ""
        private var attribname = ""
        private val attribvalue = StringBuilder()
        private var attribs: ArrayList<Pair<String, String>>? = null
        private val stack = ArrayList<String>() // last element is the top (htmlparser2's stack[0])
        private val foreignContext = ArrayList<Boolean>().also { it.add(false) }

        // domhandler state
        private val root = HElement(null, emptyList())
        private val tagStack = ArrayList<HElement>().also { it.add(root) }
        private var lastNode: HNode? = null

        fun parse(): HElement {
            val len = buffer.length
            while (index < len) {
                val c = buffer[index].code
                when (state) {
                    TEXT -> stateText(c)
                    SPECIAL_START_SEQUENCE -> stateSpecialStartSequence(c)
                    IN_SPECIAL_TAG -> stateInSpecialTag(c)
                    CDATA_SEQUENCE -> stateCdataSequence(c)
                    IN_ATTRIBUTE_VALUE_DQ -> handleInAttributeValue(c, '"'.code)
                    IN_ATTRIBUTE_NAME -> stateInAttributeName(c)
                    IN_COMMENT_LIKE -> stateInCommentLike(c)
                    IN_SPECIAL_COMMENT -> stateInSpecialComment(c)
                    BEFORE_ATTRIBUTE_NAME -> stateBeforeAttributeName(c)
                    IN_TAG_NAME -> stateInTagName(c)
                    IN_CLOSING_TAG_NAME -> stateInClosingTagName(c)
                    BEFORE_TAG_NAME -> stateBeforeTagName(c)
                    AFTER_ATTRIBUTE_NAME -> stateAfterAttributeName(c)
                    IN_ATTRIBUTE_VALUE_SQ -> handleInAttributeValue(c, '\''.code)
                    BEFORE_ATTRIBUTE_VALUE -> stateBeforeAttributeValue(c)
                    BEFORE_CLOSING_TAG_NAME -> stateBeforeClosingTagName(c)
                    AFTER_CLOSING_TAG_NAME -> stateAfterClosingTagName(c)
                    BEFORE_SPECIAL_S -> stateBeforeSpecialS(c)
                    BEFORE_SPECIAL_T -> stateBeforeSpecialT(c)
                    IN_ATTRIBUTE_VALUE_NQ -> stateInAttributeValueNoQuotes(c)
                    IN_SELF_CLOSING_TAG -> stateInSelfClosingTag(c)
                    IN_DECLARATION -> stateInDeclaration(c)
                    BEFORE_DECLARATION -> stateBeforeDeclaration(c)
                    BEFORE_COMMENT -> stateBeforeComment(c)
                    IN_PROCESSING_INSTRUCTION -> stateInProcessingInstruction(c)
                    IN_ENTITY -> stateInEntity()
                }
                index++
            }
            cleanup()
            finish()
            return root
        }

        private fun seq(i: Int): Int = currentSequence[i].code

        private fun stateText(c: Int) {
            if (c == '<'.code) {
                if (index > sectionStart) onText(sectionStart, index)
                state = BEFORE_TAG_NAME
                sectionStart = index
            } else if (c == '&'.code) {
                startEntity()
            }
        }

        private fun stateSpecialStartSequence(c: Int) {
            val isEnd = sequenceIndex == currentSequence.length
            val isMatch = if (isEnd) isEndOfTagSection(c) else (c or 0x20) == seq(sequenceIndex)
            if (!isMatch) {
                isSpecial = false
            } else if (!isEnd) {
                sequenceIndex++
                return
            }
            sequenceIndex = 0
            state = IN_TAG_NAME
            stateInTagName(c)
        }

        private fun stateInSpecialTag(c: Int) {
            if (sequenceIndex == currentSequence.length) {
                if (c == '>'.code || isWhitespace(c)) {
                    val endOfText = index - currentSequence.length
                    if (sectionStart < endOfText) {
                        val actual = index
                        index = endOfText
                        onText(sectionStart, endOfText)
                        index = actual
                    }
                    isSpecial = false
                    sectionStart = endOfText + 2
                    stateInClosingTagName(c)
                    return
                }
                sequenceIndex = 0
            }
            if ((c or 0x20) == seq(sequenceIndex)) {
                sequenceIndex += 1
            } else if (sequenceIndex == 0) {
                if (currentSequence == TITLE_END) {
                    if (c == '&'.code) startEntity()
                } else if (fastForwardTo('<'.code)) {
                    sequenceIndex = 1
                }
            } else {
                sequenceIndex = if (c == '<'.code) 1 else 0
            }
        }

        private fun stateCdataSequence(c: Int) {
            if (c == CDATA[sequenceIndex].code) {
                if (++sequenceIndex == CDATA.length) {
                    state = IN_COMMENT_LIKE
                    currentSequence = CDATA_END
                    sequenceIndex = 0
                    sectionStart = index + 1
                }
            } else {
                sequenceIndex = 0
                state = IN_DECLARATION
                stateInDeclaration(c)
            }
        }

        private fun fastForwardTo(c: Int): Boolean {
            while (++index < buffer.length) {
                if (buffer[index].code == c) return true
            }
            index = buffer.length - 1
            return false
        }

        private fun stateInCommentLike(c: Int) {
            if (c == seq(sequenceIndex)) {
                if (++sequenceIndex == currentSequence.length) {
                    if (currentSequence == CDATA_END) onCdata(sectionStart, index, 2) else onComment(sectionStart, index, 2)
                    sequenceIndex = 0
                    sectionStart = index + 1
                    state = TEXT
                }
            } else if (sequenceIndex == 0) {
                if (fastForwardTo(seq(0))) sequenceIndex = 1
            } else if (c != seq(sequenceIndex - 1)) {
                sequenceIndex = 0
            }
        }

        private fun startSpecial(sequence: String, offset: Int) {
            isSpecial = true
            currentSequence = sequence
            sequenceIndex = offset
            state = SPECIAL_START_SEQUENCE
        }

        private fun stateBeforeTagName(c: Int) {
            when {
                c == '!'.code -> {
                    state = BEFORE_DECLARATION
                    sectionStart = index + 1
                }
                c == '?'.code -> {
                    state = IN_PROCESSING_INSTRUCTION
                    sectionStart = index + 1
                }
                isAsciiAlpha(c) -> {
                    val lower = c or 0x20
                    sectionStart = index
                    state = when (lower) {
                        's'.code -> BEFORE_SPECIAL_S
                        't'.code, 'x'.code -> BEFORE_SPECIAL_T
                        else -> IN_TAG_NAME
                    }
                }
                c == '/'.code -> state = BEFORE_CLOSING_TAG_NAME
                else -> {
                    state = TEXT
                    stateText(c)
                }
            }
        }

        private fun stateInTagName(c: Int) {
            if (isEndOfTagSection(c)) {
                onOpenTagName(sectionStart, index)
                sectionStart = -1
                state = BEFORE_ATTRIBUTE_NAME
                stateBeforeAttributeName(c)
            }
        }

        private fun stateBeforeClosingTagName(c: Int) {
            if (isWhitespace(c)) {
                // ignore
            } else if (c == '>'.code) {
                state = TEXT
            } else {
                state = if (isAsciiAlpha(c)) IN_CLOSING_TAG_NAME else IN_SPECIAL_COMMENT
                sectionStart = index
            }
        }

        private fun stateInClosingTagName(c: Int) {
            if (c == '>'.code || isWhitespace(c)) {
                onCloseTag(sectionStart, index)
                sectionStart = -1
                state = AFTER_CLOSING_TAG_NAME
                stateAfterClosingTagName(c)
            }
        }

        private fun stateAfterClosingTagName(c: Int) {
            if (c == '>'.code || fastForwardTo('>'.code)) {
                state = TEXT
                sectionStart = index + 1
            }
        }

        private fun stateBeforeAttributeName(c: Int) {
            if (c == '>'.code) {
                onOpenTagEnd(index)
                if (isSpecial) {
                    state = IN_SPECIAL_TAG
                    sequenceIndex = 0
                } else {
                    state = TEXT
                }
                sectionStart = index + 1
            } else if (c == '/'.code) {
                state = IN_SELF_CLOSING_TAG
            } else if (!isWhitespace(c)) {
                state = IN_ATTRIBUTE_NAME
                sectionStart = index
            }
        }

        private fun stateInSelfClosingTag(c: Int) {
            if (c == '>'.code) {
                onSelfClosingTag(index)
                state = TEXT
                sectionStart = index + 1
                isSpecial = false
            } else if (!isWhitespace(c)) {
                state = BEFORE_ATTRIBUTE_NAME
                stateBeforeAttributeName(c)
            }
        }

        private fun stateInAttributeName(c: Int) {
            if (c == '='.code || isEndOfTagSection(c)) {
                onAttribName(sectionStart, index)
                sectionStart = index
                state = AFTER_ATTRIBUTE_NAME
                stateAfterAttributeName(c)
            }
        }

        private fun stateAfterAttributeName(c: Int) {
            if (c == '='.code) {
                state = BEFORE_ATTRIBUTE_VALUE
            } else if (c == '/'.code || c == '>'.code) {
                onAttribEnd()
                sectionStart = -1
                state = BEFORE_ATTRIBUTE_NAME
                stateBeforeAttributeName(c)
            } else if (!isWhitespace(c)) {
                onAttribEnd()
                state = IN_ATTRIBUTE_NAME
                sectionStart = index
            }
        }

        private fun stateBeforeAttributeValue(c: Int) {
            if (c == '"'.code) {
                state = IN_ATTRIBUTE_VALUE_DQ
                sectionStart = index + 1
            } else if (c == '\''.code) {
                state = IN_ATTRIBUTE_VALUE_SQ
                sectionStart = index + 1
            } else if (!isWhitespace(c)) {
                sectionStart = index
                state = IN_ATTRIBUTE_VALUE_NQ
                stateInAttributeValueNoQuotes(c)
            }
        }

        private fun handleInAttributeValue(c: Int, quote: Int) {
            if (c == quote) {
                onAttribData(sectionStart, index)
                sectionStart = -1
                onAttribEnd()
                state = BEFORE_ATTRIBUTE_NAME
            } else if (c == '&'.code) {
                startEntity()
            }
        }

        private fun stateInAttributeValueNoQuotes(c: Int) {
            if (isWhitespace(c) || c == '>'.code) {
                onAttribData(sectionStart, index)
                sectionStart = -1
                onAttribEnd()
                state = BEFORE_ATTRIBUTE_NAME
                stateBeforeAttributeName(c)
            } else if (c == '&'.code) {
                startEntity()
            }
        }

        private fun stateBeforeDeclaration(c: Int) {
            if (c == '['.code) {
                state = CDATA_SEQUENCE
                sequenceIndex = 0
            } else {
                state = if (c == '-'.code) BEFORE_COMMENT else IN_DECLARATION
            }
        }

        private fun stateInDeclaration(c: Int) {
            if (c == '>'.code || fastForwardTo('>'.code)) {
                onDeclaration(sectionStart, index)
                state = TEXT
                sectionStart = index + 1
            }
        }

        private fun stateInProcessingInstruction(c: Int) {
            if (c == '>'.code || fastForwardTo('>'.code)) {
                onProcessingInstruction(sectionStart, index)
                state = TEXT
                sectionStart = index + 1
            }
        }

        private fun stateBeforeComment(c: Int) {
            if (c == '-'.code) {
                state = IN_COMMENT_LIKE
                currentSequence = COMMENT_END
                sequenceIndex = 2
                sectionStart = index + 1
            } else {
                state = IN_DECLARATION
            }
        }

        private fun stateInSpecialComment(c: Int) {
            if (c == '>'.code || fastForwardTo('>'.code)) {
                onComment(sectionStart, index, 0)
                state = TEXT
                sectionStart = index + 1
            }
        }

        private fun stateBeforeSpecialS(c: Int) {
            val lower = c or 0x20
            when (lower) {
                SCRIPT_END[3].code -> startSpecial(SCRIPT_END, 4)
                STYLE_END[3].code -> startSpecial(STYLE_END, 4)
                else -> {
                    state = IN_TAG_NAME
                    stateInTagName(c)
                }
            }
        }

        private fun stateBeforeSpecialT(c: Int) {
            when (c or 0x20) {
                TITLE_END[3].code -> startSpecial(TITLE_END, 4)
                TEXTAREA_END[3].code -> startSpecial(TEXTAREA_END, 4)
                XMP_END[3].code -> startSpecial(XMP_END, 4)
                else -> {
                    state = IN_TAG_NAME
                    stateInTagName(c)
                }
            }
        }

        private fun startEntity() {
            baseState = state
            state = IN_ENTITY
            entityStart = index
            entityDecoder.startEntity(
                if (baseState == TEXT || baseState == IN_SPECIAL_TAG) EntityDecoder.Mode.LEGACY else EntityDecoder.Mode.ATTRIBUTE,
            )
        }

        private fun stateInEntity() {
            val length = entityDecoder.write(buffer, index)
            if (length >= 0) {
                state = baseState
                if (length == 0) index -= 1
            } else {
                if (index < buffer.length && buffer[index] == '&') {
                    state = baseState
                    index -= 1
                    return
                }
                index = buffer.length - 1
            }
        }

        private fun cleanup() {
            if (sectionStart != index) {
                if (state == TEXT || (state == IN_SPECIAL_TAG && sequenceIndex == 0)) {
                    onText(sectionStart, index)
                    sectionStart = index
                } else if (state == IN_ATTRIBUTE_VALUE_DQ || state == IN_ATTRIBUTE_VALUE_SQ || state == IN_ATTRIBUTE_VALUE_NQ) {
                    onAttribData(sectionStart, index)
                    sectionStart = index
                }
            }
        }

        private fun finish() {
            if (state == IN_ENTITY) {
                entityDecoder.end()
                state = baseState
            }
            handleTrailingData()
            onEnd()
        }

        private fun handleTrailingData() {
            val endIndex = buffer.length
            if (sectionStart >= endIndex) return
            when (state) {
                IN_COMMENT_LIKE -> if (currentSequence == CDATA_END) onCdata(sectionStart, endIndex, 0) else onComment(sectionStart, endIndex, 0)
                IN_TAG_NAME, BEFORE_ATTRIBUTE_NAME, BEFORE_ATTRIBUTE_VALUE, AFTER_ATTRIBUTE_NAME, IN_ATTRIBUTE_NAME,
                IN_ATTRIBUTE_VALUE_SQ, IN_ATTRIBUTE_VALUE_DQ, IN_ATTRIBUTE_VALUE_NQ, IN_CLOSING_TAG_NAME -> {}
                else -> onText(sectionStart, endIndex)
            }
        }

        private fun emitCodePoint(cp: Int, consumed: Int) {
            if (baseState != TEXT && baseState != IN_SPECIAL_TAG) {
                if (sectionStart < entityStart) onAttribData(sectionStart, entityStart)
                sectionStart = entityStart + consumed
                index = sectionStart - 1
                val sb = StringBuilder(2)
                Js.appendCodePoint(sb, cp)
                attribvalue.append(sb)
            } else {
                if (sectionStart < entityStart) onText(sectionStart, entityStart)
                sectionStart = entityStart + consumed
                index = sectionStart - 1
                val sb = StringBuilder(2)
                Js.appendCodePoint(sb, cp)
                handleText(sb.toString())
            }
        }

        // ---- Parser callbacks ----

        private fun slice(start: Int, end: Int): String =
            buffer.substring(start.coerceIn(0, buffer.length), end.coerceIn(0, buffer.length).coerceAtLeast(start.coerceIn(0, buffer.length)))

        private fun onText(start: Int, end: Int) = handleText(slice(start, end))

        private fun onOpenTagName(start: Int, end: Int) = emitOpenTag(slice(start, end))

        private fun emitOpenTag(name: String) {
            tagname = name
            val impliesClose = OPEN_IMPLIES_CLOSE[name]
            if (impliesClose != null) {
                while (stack.isNotEmpty() && stack[stack.size - 1] in impliesClose) {
                    stack.removeAt(stack.size - 1)
                    handlerCloseTag()
                }
            }
            if (name !in VOID) {
                stack.add(name)
                if (name in FOREIGN_CONTEXT) {
                    foreignContext.add(true)
                } else if (name in HTML_INTEGRATION) {
                    foreignContext.add(false)
                }
            }
            attribs = ArrayList()
        }

        private fun endOpenTag() {
            attribs?.let { handlerOpenTag(tagname, it) }
            attribs = null
            if (tagname in VOID) handlerCloseTag()
            tagname = ""
        }

        private fun onOpenTagEnd(@Suppress("UNUSED_PARAMETER") end: Int) = endOpenTag()

        private fun onCloseTag(start: Int, end: Int) {
            val name = slice(start, end)
            if (name in FOREIGN_CONTEXT || name in HTML_INTEGRATION) {
                if (foreignContext.isNotEmpty()) foreignContext.removeAt(foreignContext.size - 1)
            }
            if (name !in VOID) {
                val pos = stack.lastIndexOf(name)
                if (pos != -1) {
                    val count = stack.size - pos
                    repeat(count) {
                        stack.removeAt(stack.size - 1)
                        handlerCloseTag()
                    }
                } else if (name == "p") {
                    emitOpenTag("p")
                    closeCurrentTag()
                }
            } else if (name == "br") {
                handlerOpenTag("br", ArrayList())
                handlerCloseTag()
            }
        }

        private fun onSelfClosingTag(end: Int) {
            if (foreignContext.lastOrNull() == true) {
                closeCurrentTag()
            } else {
                onOpenTagEnd(end)
            }
        }

        private fun closeCurrentTag() {
            val name = tagname
            endOpenTag()
            if (stack.isNotEmpty() && stack[stack.size - 1] == name) {
                handlerCloseTag()
                stack.removeAt(stack.size - 1)
            }
        }

        private fun onAttribName(start: Int, end: Int) {
            attribname = slice(start, end)
        }

        private fun onAttribData(start: Int, end: Int) {
            attribvalue.append(slice(start, end))
        }

        private fun onAttribEnd() {
            val list = attribs
            if (list != null && attribname != "__proto__" && list.none { it.first == attribname }) {
                list.add(attribname to attribvalue.toString())
            }
            attribvalue.setLength(0)
        }

        private fun onDeclaration(start: Int, end: Int) {
            handlerProcessingInstruction("!" + slice(start, end))
        }

        private fun onProcessingInstruction(start: Int, end: Int) {
            handlerProcessingInstruction("?" + slice(start, end))
        }

        private fun onComment(start: Int, end: Int, offset: Int) {
            handlerComment(slice(start, end - offset))
            lastNode = null
        }

        private fun onCdata(start: Int, end: Int, offset: Int) {
            // htmlMode without recognizeCDATA: CDATA becomes a comment.
            handlerComment("[CDATA[" + slice(start, end - offset) + "]]")
            lastNode = null
        }

        private fun onEnd() {
            for (i in stack.indices) handlerCloseTag()
        }

        // ---- domhandler ----

        private fun addNode(node: HNode) {
            val parent = tagStack.lastOrNull() ?: return
            parent.children.add(node)
            node.parent = parent
            lastNode = null
        }

        private fun handlerOpenTag(name: String, attrs: List<Pair<String, String>>) {
            val el = HElement(name, attrs)
            addNode(el)
            tagStack.add(el)
        }

        private fun handlerCloseTag() {
            lastNode = null
            if (tagStack.isNotEmpty()) tagStack.removeAt(tagStack.size - 1)
        }

        private fun handleText(data: String) {
            val last = lastNode
            if (last is HText) {
                last.buf.append(data)
            } else {
                val node = HText(data)
                addNode(node)
                lastNode = node
            }
        }

        private fun handlerComment(data: String) {
            val last = lastNode
            if (last is HComment) {
                last.data += data
                return
            }
            val node = HComment(data)
            addNode(node)
            lastNode = node
        }

        private fun handlerProcessingInstruction(data: String) {
            addNode(HDirective(data))
        }
    }

    private val VOID = SINGLE_TAG
}
