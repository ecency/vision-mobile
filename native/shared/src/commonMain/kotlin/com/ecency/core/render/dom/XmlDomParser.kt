package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
/** xmldom's `ParseError`: the input is too malformed for xmldom (render-helper then repairs it). */
class DomParseException(message: String) : RuntimeException(message)

/**
 * Port of `@xmldom/xmldom` 0.9.12 `DOMParser.parseFromString(source, "text/html")` with the
 * swallow-all `onError` handler render-helper installs (`consts/dom-parser.const.ts`):
 * warnings and errors are ignored, fatal errors still throw [DomParseException].
 *
 * The scanner is xmldom's own (`sax.js`): it is not an HTML5 tree builder. Tags must nest
 * exactly, end tags match case-insensitively, void elements self-close, script/style/textarea/
 * title contents are raw text, and entities are expanded from xmldom's HTML entity table.
 */
object XmlDomParser {
    fun parseFromString(source: String): Document {
        val builder = DomBuilder()
        SaxParser(normalizeLineEndings(source), builder).parse()
        builder.doc.normalize()
        if (builder.doc.documentElement == null) throw DomParseException("missing root element")
        return builder.doc
    }

    private fun normalizeLineEndings(input: String): String {
        var needs = false
        for (c in input) {
            if (c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                needs = true
                break
            }
        }
        if (!needs) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\r') {
                sb.append('\n')
                if (i + 1 < input.length && (input[i + 1] == '\n' || input[i + 1] == '\u0085')) i++
            } else if (c == '\u0085' || c == '\u2028' || c == '\u2029') {
                sb.append('\n')
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}

/**
 * A plain JS `Error` thrown inside the sax loop: xmldom reports it and emits the `<` as text.
 * One shared instance: building a stack trace per stray `<` would dominate the parse time.
 */
private object SaxRecoverable : RuntimeException()

/** Namespace scope, inheriting from the parent the way xmldom's prototype chain does. */
private class NsMap(private val parent: NsMap?) {
    private var own: HashMap<String, String>? = null
    fun put(prefix: String, uri: String) {
        (own ?: HashMap<String, String>().also { own = it })[prefix] = uri
    }

    operator fun get(prefix: String): String? {
        var m: NsMap? = this
        while (m != null) {
            m.own?.let { if (it.containsKey(prefix)) return it[prefix] }
            m = m.parent
        }
        return null
    }
}

private class SaxAttr(val qName: String, val value: String) {
    var prefix: String? = null
    var localName: String = qName
    var uri: String? = null
}

private class ElementAttributes {
    var tagName: String? = null
    val attrs = ArrayList<SaxAttr>(4)
    val names = HashSet<String>()
    var closed = false
    var nsMap: NsMap? = null

    /** False for an invalid name (xmldom throws a plain Error there). */
    fun assignTagName(name: String): Boolean {
        if (!XmlGrammar.isQName(name)) return false
        tagName = name
        return true
    }

    fun addValue(qName: String, value: String): Boolean {
        if (!XmlGrammar.isQName(qName)) return false
        names.add(qName)
        attrs.add(SaxAttr(qName, value))
        return true
    }
}

/** xmldom's DOMHandler (the DOM builder side of the sax parser). */
private class DomBuilder {
    val doc = Document()
    var currentElement: Node? = null
    var cdata = false

    fun startElement(ns: String?, qName: String, el: ElementAttributes) {
        val element = doc.createElementNSChecked(ns, qName)
        append(element)
        currentElement = element
        element.setParsedAttributes(el.attrs.map { doc.createAttributeNS(it.uri, it.qName, it.value, checkedName = true) })
    }

    fun endElement() {
        val cur = currentElement ?: throw SaxRecoverable
        currentElement = cur.parentNode
    }

    private fun append(node: Node) {
        val cur = currentElement
        if (cur == null) doc.appendChild(node) else cur.appendChild(node)
    }

    fun characters(chars: String) {
        if (chars.isEmpty()) return
        val node: Node = if (cdata) doc.createCDATASection(chars) else doc.createTextNode(chars)
        val cur = currentElement
        if (cur != null) {
            cur.appendChild(node)
        } else if (Js.isBlank(chars)) {
            doc.appendChild(node)
        }
    }

    fun comment(data: String) = append(doc.createComment(data))

    fun processingInstruction(target: String, data: String) = append(doc.createProcessingInstruction(target, data))

    fun doctype(name: String, publicId: String?, systemId: String?) {
        val dt = DocumentType(doc, name, publicId, systemId, null)
        append(dt)
        doc.doctype = dt
    }
}

private class SaxParser(private val source: String, private val b: DomBuilder) {
    private val entityMap = EntityTables.XMLDOM_HTML
    private var start = 0
    private val parseStack = ArrayList<NsMap>()
    private val unclosedTags = ArrayList<String>()

    // Linear-time guards for inputs that would make xmldom rescan to the end over and over:
    // the last quote of each kind, and raw-text tags known to have no closing tag after a point.
    private val lastDoubleQuote = source.lastIndexOf('"')
    private val lastSingleQuote = source.lastIndexOf('\'')
    private val noCloseAfter = HashMap<String, Int>()

    private fun fatal(msg: String): Nothing = throw DomParseException(msg)

    /** `/&#?\w+;?/g` replaced through xmldom's entityReplacer. */
    private fun replaceEntities(s: String): String {
        var amp = s.indexOf('&')
        if (amp < 0) return s
        val sb = StringBuilder(s.length)
        var last = 0
        while (amp >= 0) {
            var p = amp + 1
            if (p < s.length && s[p] == '#') p++
            val wordStart = p
            while (p < s.length && Js.isWordChar(s[p])) p++
            if (p == wordStart) {
                amp = s.indexOf('&', amp + 1)
                continue
            }
            if (p < s.length && s[p] == ';') p++
            sb.append(s, last, amp)
            sb.append(entityReplacement(s.substring(amp, p)))
            last = p
            amp = s.indexOf('&', p)
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    private fun entityReplacement(a: String): String {
        val complete = if (a.endsWith(";")) a else "$a;"
        val k = complete.substring(1, complete.length - 1)
        if (!isReference(k)) return a
        entityMap[k]?.let { return it }
        if (k[0] == '#') {
            val code = if (k.length > 1 && k[1] == 'x') Js.digitsToDouble(k.substring(2), 16) else Js.digitsToDouble(k.substring(1), 10)
            return fromCharCodeFixed(code)
        }
        return a
    }

    /** Whether `&k;` matches xmldom's `Reference` production (k is made of `\w` chars and `#`). */
    private fun isReference(k: String): Boolean {
        if (k.isEmpty()) return false
        val c = k[0]
        if (c == '#') {
            if (k.length >= 3 && k[1] == 'x') {
                for (i in 2 until k.length) if (!Js.isHexDigit(k[i])) return false
                return true
            }
            if (k.length < 2) return false
            for (i in 1 until k.length) if (!Js.isDigit(k[i])) return false
            return true
        }
        return Js.isAsciiAlpha(c) || c == '_'
    }

    /** xmldom `fixedFromCharCode`, including JS ToInt32/ToUint16 wrapping for huge values. */
    private fun fromCharCodeFixed(code0: Double): String {
        if (code0 > 0xFFFF) {
            val code = code0 - 0x10000
            val s1 = 0xD800 + (Js.toInt32(code) shr 10)
            val s2 = 0xDC00 + (Js.toInt32(code) and 0x3FF)
            return charArrayOf(Js.toUint16(s1.toDouble()), Js.toUint16(s2.toDouble())).concatToString()
        }
        return Js.toUint16(code0).toString()
    }

    private fun appendText(end: Int) {
        if (end > start) {
            b.characters(replaceEntities(source.substring(start, end)))
            start = end
        }
    }

    fun parse() {
        parseStack.add(defaultNs())
        while (true) {
            val tagStart = source.indexOf('<', start)
            var end: Int
            try {
                if (tagStart < 0) {
                    if (!Js.isBlank(source.substring(start))) {
                        if (b.doc.documentElement != null) return
                        val text = b.doc.createTextNode(source.substring(start))
                        b.doc.appendChild(text)
                        b.currentElement = text
                    }
                    return
                }
                if (tagStart > start) appendText(tagStart)
                val next = if (tagStart + 1 < source.length) source[tagStart + 1] else '\u0000'
                end = when {
                    tagStart + 1 < source.length && next == '/' -> endTag(tagStart) ?: return
                    tagStart + 1 < source.length && next == '?' -> processingInstruction(tagStart)
                    tagStart + 1 < source.length && next == '!' -> doctypeCommentOrCData(tagStart)
                    else -> startTag(tagStart)
                }
            } catch (e: DomParseException) {
                throw e
            } catch (e: DomException) {
                fatal("Error constructing the DOM: ${e.message}")
            } catch (e: SaxRecoverable) {
                end = -1
            }
            if (end > start) {
                start = end
            } else {
                appendText(maxOf(tagStart, start) + 1)
            }
        }
    }

    private fun defaultNs(): NsMap = NsMap(null).also {
        it.put("", Namespaces.HTML)
        it.put("xml", Namespaces.XML)
    }

    /** Returns the next index, or null when xmldom stops parsing (`return` from parse). */
    private fun endTag(tagStart: Int): Int? {
        val gt = source.indexOf('>', tagStart + 2)
        val raw = if (gt > 0) source.substring(tagStart + 2, gt) else source.substring(tagStart + 2)
        if (raw.isEmpty()) fatal("end tag name missing")
        val nameLen = if (gt > 0) XmlGrammar.qNamePrefix(raw, 0) else 0
        if (nameLen == 0) fatal("end tag name contains invalid characters: \"$raw\"")
        val name = raw.substring(0, nameLen)
        if (b.currentElement == null && b.doc.documentElement == null) return null
        val currentTagName = unclosedTags.lastOrNull()
            ?: when (val cur = b.currentElement) {
                null -> throw SaxRecoverable
                is Element -> cur.tagName
                is Document -> b.doc.documentElement?.tagName ?: ""
                else -> b.doc.documentElement?.tagName ?: ""
            }
        if (currentTagName != name && Js.lower(currentTagName) != Js.lower(name)) {
            fatal("Opening and ending tag mismatch: \"$currentTagName\" != \"$raw\"")
        }
        val hadConfig = parseStack.isNotEmpty()
        if (hadConfig) parseStack.removeAt(parseStack.size - 1)
        if (unclosedTags.isNotEmpty()) unclosedTags.removeAt(unclosedTags.size - 1)
        if (!hadConfig) throw SaxRecoverable
        b.endElement()
        return gt + 1
    }

    private fun processingInstruction(tagStart: Int): Int {
        val pi = XmlGrammar.matchPi(source, tagStart) ?: fatal("Invalid processing instruction starting at position $tagStart")
        if (Js.lower(pi.target) == "xml" && (tagStart > 0 || !isXmlDecl())) fatal("misplaced or malformed xml declaration")
        b.processingInstruction(pi.target, pi.data ?: "")
        return pi.end
    }

    /** xmldom `XMLDecl` at the very start of the source. */
    private fun isXmlDecl(): Boolean {
        val s = source
        if (!s.startsWith("<?xml")) return false
        var p = 5
        fun spaces(min: Int): Boolean {
            val q = skipBlanks(p)
            if (q - p < min) return false
            p = q
            return true
        }
        fun eq(): Boolean {
            p = skipBlanks(p)
            if (p >= s.length || s[p] != '=') return false
            p = skipBlanks(p + 1)
            return true
        }
        fun quotedValue(valid: (String) -> Boolean): Boolean {
            if (p >= s.length || (s[p] != '"' && s[p] != '\'')) return false
            val close = s.indexOf(s[p], p + 1)
            if (close < 0 || !valid(s.substring(p + 1, close))) return false
            p = close + 1
            return true
        }
        if (!spaces(1) || !s.startsWith("version", p)) return false
        p += 7
        if (!eq() || !quotedValue { v -> v.length > 2 && v.startsWith("1.") && v.substring(2).all(Js::isDigit) }) return false
        val save1 = p
        if (spaces(1) && s.startsWith("encoding", p)) {
            p += 8
            val ok = eq() && quotedValue { v ->
                v.isNotEmpty() && Js.isAsciiAlpha(v[0]) && v.all { Js.isAsciiAlpha(it) || Js.isDigit(it) || it in "-._" }
            }
            if (!ok) p = save1
        } else {
            p = save1
        }
        val save2 = p
        if (spaces(1) && s.startsWith("standalone", p)) {
            p += 10
            if (!(eq() && quotedValue { it == "yes" || it == "no" })) p = save2
        } else {
            p = save2
        }
        p = skipBlanks(p)
        return s.startsWith("?>", p)
    }

    private fun doctypeCommentOrCData(tagStart: Int): Int {
        val c2 = if (tagStart + 2 < source.length) source[tagStart + 2] else '\u0000'
        when {
            c2 == '-' -> {
                val end = XmlGrammar.matchComment(source, tagStart)
                if (end < 0) fatal("comment is not well-formed")
                b.comment(source.substring(tagStart + 4, end - 3))
                return end
            }
            c2 == '[' -> {
                val end = XmlGrammar.matchCData(source, tagStart)
                if (end < 0) fatal("Invalid CDATA")
                b.cdata = true
                b.characters(source.substring(tagStart + 9, end - 3))
                b.cdata = false
                return end
            }
            c2 == 'D' || c2 == 'd' -> return doctype(tagStart)
            else -> fatal("Not well-formed XML starting with \"<!\"")
        }
    }

    private fun skipBlanks(from: Int): Int {
        var p = from
        while (p < source.length && XmlGrammar.isS(source[p])) p++
        return p
    }

    private fun doctype(tagStart: Int): Int {
        if (b.doc.documentElement != null) fatal("Doctype not allowed inside or after documentElement")
        if (!Js.regionMatchesLower(source, tagStart, "<!doctype")) fatal("Expected <!DOCTYPE")
        var p = tagStart + 9
        val afterBlanks = skipBlanks(p)
        if (afterBlanks == p) fatal("Expected whitespace after <!DOCTYPE")
        p = afterBlanks
        val n = XmlGrammar.name(source, p)
        if (n == 0) fatal("doctype name missing")
        val name = source.substring(p, p + n)
        p = skipBlanks(p + n)
        var publicId: String? = null
        var systemId: String? = null
        if (source.startsWith("PUBLIC", p) || source.startsWith("SYSTEM", p)) {
            val m = externalId(p) ?: fatal("doctype external id is not well-formed")
            publicId = m.first
            systemId = m.second
            p = m.third
        } else if (Js.regionMatchesLower(source, p, "system")) {
            p += 6
            val q = skipBlanks(p)
            if (q == p) fatal("Expected whitespace after SYSTEM")
            p = q
            val lit = legacyCompat(p) ?: fatal("Expected about:legacy-compat")
            systemId = lit
            p += lit.length
        }
        p = skipBlanks(p)
        if (p >= source.length || source[p] != '>') fatal("doctype not terminated with >")
        b.doctype(name, publicId, systemId)
        return p + 1
    }

    private fun legacyCompat(p: Int): String? {
        for (q in charArrayOf('"', '\'')) {
            val lit = "${q}about:legacy-compat$q"
            if (source.startsWith(lit, p)) return lit
        }
        return null
    }

    private fun quoted(p: Int, allowed: (Char) -> Boolean): Int {
        if (p >= source.length) return -1
        val q = source[p]
        if (q != '"' && q != '\'') return -1
        var i = p + 1
        while (i < source.length && source[i] != q) {
            if (!allowed(source[i])) return -1
            i++
        }
        return if (i < source.length) i + 1 else -1
    }

    private fun isPubidChar(c: Char): Boolean =
        c == ' ' || c == '\r' || c == '\n' || Js.isAsciiAlpha(c) || Js.isDigit(c) || c in "-'()+,./:=?;!*#@\$_%"

    /** `ExternalID_match`: (publicId, systemId, end). */
    private fun externalId(p: Int): Triple<String?, String?, Int>? {
        if (source.startsWith("SYSTEM", p)) {
            val s = skipBlanks(p + 6)
            if (s == p + 6) return null
            val e = quoted(s) { true }
            return if (e < 0) null else Triple(null, source.substring(s, e), e)
        }
        val s = skipBlanks(p + 6)
        if (s == p + 6) return null
        val pubQuote = if (s < source.length) source[s] else ' '
        val pe = quoted(s) { c -> isPubidChar(c) && !(pubQuote == '\'' && c == '\'') }
        if (pe < 0) return null
        val s2 = skipBlanks(pe)
        if (s2 == pe) return null
        val se = quoted(s2) { true }
        return if (se < 0) null else Triple(source.substring(s, pe), source.substring(s2, se), se)
    }

    private fun startTag(tagStart: Int): Int {
        val el = ElementAttributes()
        val currentNs = parseStack.lastOrNull() ?: throw SaxRecoverable
        var end = parseElementStartPart(tagStart, el)
        if (end == FAIL) return -1
        if (!el.closed) {
            if (isVoid(el.tagName!!)) el.closed = true else unclosedTags.add(el.tagName!!)
        }
        if (appendElement(el, currentNs)) parseStack.add(el.nsMap!!)
        end = if (!el.closed) htmlSpecialContent(end, el.tagName!!) else end + 1
        return end
    }

    /** False for a recoverable error; a duplicate attribute is fatal. */
    private fun addAttribute(el: ElementAttributes, qName: String, value: String): Boolean {
        if (qName in el.names) fatal("Attribute $qName redefined")
        var v = value
        if (v.indexOf('\t') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }
        return el.addValue(qName, replaceEntities(v))
    }

    /** xmldom `parseElementStartPart`; returns the index of the closing `>` (or end of input). */
    private fun parseElementStartPart(tagStart: Int, el: ElementAttributes): Int {
        var attrName = ""
        var start = tagStart + 1
        var p = start
        var s = S_TAG
        while (true) {
            if (p >= source.length) {
                if (s == S_TAG) if (!el.assignTagName(source.substring(start, p))) return FAIL
                return p
            }
            var c = source[p]
            if (s == S_TAG && c == '<') return FAIL
            when (c) {
                '=' -> when (s) {
                    S_ATTR -> {
                        attrName = source.substring(start, p)
                        s = S_EQ
                    }
                    S_ATTR_SPACE -> s = S_EQ
                    else -> return FAIL
                }
                '\'', '"' -> when (s) {
                    S_EQ, S_ATTR -> {
                        if (s == S_ATTR) attrName = source.substring(start, p)
                        start = p + 1
                        p = if (start > (if (c == '"') lastDoubleQuote else lastSingleQuote)) -1 else source.indexOf(c, start)
                        if (p > 0) {
                            if (!addAttribute(el, attrName, source.substring(start, p))) return FAIL
                            s = S_ATTR_END
                        } else {
                            return FAIL
                        }
                    }
                    S_ATTR_NOQUOT_VALUE -> {
                        if (!addAttribute(el, attrName, source.substring(start, p))) return FAIL
                        start = p + 1
                        s = S_ATTR_END
                    }
                    else -> return FAIL
                }
                '/' -> when (s) {
                    S_TAG -> {
                        if (!el.assignTagName(source.substring(start, p))) return FAIL
                        s = S_TAG_CLOSE
                        el.closed = true
                    }
                    S_ATTR_END, S_TAG_SPACE, S_TAG_CLOSE -> {
                        s = S_TAG_CLOSE
                        el.closed = true
                    }
                    S_ATTR_NOQUOT_VALUE, S_ATTR -> {}
                    S_ATTR_SPACE -> el.closed = true
                    else -> return FAIL
                }
                '>' -> {
                    when (s) {
                        S_TAG -> if (!el.assignTagName(source.substring(start, p))) return FAIL
                        S_ATTR_END, S_TAG_SPACE, S_TAG_CLOSE -> {}
                        S_ATTR_NOQUOT_VALUE, S_ATTR, S_ATTR_SPACE -> {
                            var value: String
                            if (s == S_ATTR_SPACE) {
                                value = attrName
                            } else {
                                value = source.substring(start, p)
                                if (value.endsWith("/")) {
                                    el.closed = true
                                    value = value.substring(0, value.length - 1)
                                }
                            }
                            val ok = if (s == S_ATTR_NOQUOT_VALUE) addAttribute(el, attrName, value) else addAttribute(el, value, value)
                            if (!ok) return FAIL
                        }
                        S_EQ -> {}
                    }
                    return p
                }
                else -> {
                    if (c == '\u0080') c = ' '
                    if (c <= ' ') {
                        when (s) {
                            S_TAG -> {
                                if (!el.assignTagName(source.substring(start, p))) return FAIL
                                s = S_TAG_SPACE
                            }
                            S_ATTR -> {
                                attrName = source.substring(start, p)
                                s = S_ATTR_SPACE
                            }
                            S_ATTR_NOQUOT_VALUE -> {
                                if (!addAttribute(el, attrName, source.substring(start, p))) return FAIL
                                s = S_TAG_SPACE
                            }
                            S_ATTR_END -> s = S_TAG_SPACE
                        }
                    } else {
                        when (s) {
                            S_ATTR_SPACE -> {
                                if (!addAttribute(el, attrName, attrName)) return FAIL
                                start = p
                                s = S_ATTR
                            }
                            S_ATTR_END, S_TAG_SPACE -> {
                                s = S_ATTR
                                start = p
                            }
                            S_EQ -> {
                                s = S_ATTR_NOQUOT_VALUE
                                start = p
                            }
                            S_TAG_CLOSE -> return FAIL
                        }
                    }
                }
            }
            p++
        }
    }

    /** Returns true when the element stays open (it is pushed on the parse stack). */
    private fun appendElement(el: ElementAttributes, parentNs: NsMap): Boolean {
        var currentNs = parentNs
        var localDeclared = false
        for (i in el.attrs.indices.reversed()) {
            val a = el.attrs[i]
            val q = a.qName
            val nsp = q.indexOf(':')
            var nsPrefix: String? = null
            if (nsp > 0) {
                val prefix = q.substring(0, nsp)
                a.prefix = prefix
                a.localName = q.substring(nsp + 1)
                if (prefix == "xmlns") nsPrefix = a.localName
            } else {
                a.localName = q
                if (q == "xmlns") nsPrefix = ""
            }
            if (nsPrefix != null) {
                if (!localDeclared) {
                    localDeclared = true
                    currentNs = NsMap(currentNs)
                }
                currentNs.put(nsPrefix, a.value)
                a.uri = Namespaces.XMLNS
            }
        }
        for (i in el.attrs.indices.reversed()) {
            val a = el.attrs[i]
            val prefix = a.prefix
            if (!prefix.isNullOrEmpty()) {
                if (prefix == "xml") a.uri = Namespaces.XML
                if (prefix != "xmlns") a.uri = currentNs[prefix]
            }
        }
        val tagName = el.tagName!!
        val nsp = tagName.indexOf(':')
        val prefix = if (nsp > 0) tagName.substring(0, nsp) else null
        val ns = currentNs[prefix ?: ""]
        b.startElement(ns, tagName, el)
        if (el.closed) {
            b.endElement()
            return false
        }
        el.nsMap = currentNs
        return true
    }

    private fun htmlSpecialContent(elStartEnd: Int, tagName: String): Int {
        val lower = Js.lower(tagName)
        val escapable = lower == "textarea" || lower == "title"
        if (escapable || lower == "script" || lower == "style") {
            val close = "</$tagName>"
            val knownMissing = noCloseAfter[close]
            if (knownMissing != null && elStartEnd >= knownMissing) return elStartEnd + 1
            var idx = -1
            var i = source.indexOf("</", elStartEnd)
            while (i >= 0) {
                if (regionMatchesIgnoreAsciiCase(i, close)) {
                    idx = i
                    break
                }
                i = source.indexOf("</", i + 2)
            }
            if (idx < 0) {
                noCloseAfter[close] = minOf(elStartEnd, noCloseAfter[close] ?: Int.MAX_VALUE)
                return elStartEnd + 1
            }
            var text = source.substring(elStartEnd + 1, idx)
            if (escapable) text = replaceEntities(text)
            b.characters(text)
            return idx
        }
        return elStartEnd + 1
    }

    private fun regionMatchesIgnoreAsciiCase(at: Int, lit: String): Boolean {
        if (at + lit.length > source.length) return false
        for (k in lit.indices) {
            val a = source[at + k]
            val c = lit[k]
            if (a != c && Js.asciiLower(a) != Js.asciiLower(c)) return false
        }
        return true
    }

    private fun isVoid(tagName: String): Boolean = Js.lower(tagName) in VOID

    private companion object {
        /** parseElementStartPart: recoverable error (the `<` becomes text). */
        const val FAIL = -1
        const val S_TAG = 0
        const val S_ATTR = 1
        const val S_ATTR_SPACE = 2
        const val S_EQ = 3
        const val S_ATTR_NOQUOT_VALUE = 4
        const val S_ATTR_END = 5
        const val S_TAG_SPACE = 6
        const val S_TAG_CLOSE = 7
        val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    }
}
